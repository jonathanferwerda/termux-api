package com.termux.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import org.json.JSONObject;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;

/**
 * jawn-bt daemon: a persistent Bluetooth LE (Nordic UART Service) bridge for
 * jawn, replacing the Tasker + BLE-plugin middleman. Listens on a unix socket
 * at $PREFIX/var/run/jawn-bt.sock (same shared UID, so jawn connects directly),
 * keeps the GATT link up with automatic reconnects, and relays text: writes go
 * out to the device's RX characteristic, notifications come back as
 * {"event":"message","data":"..."} lines. Client: tools/jawn-bt.
 */
public class JawnBleService extends Service {

    private static final String LOG_TAG = "JawnBleService";
    public static final String ACTION_STOP = "com.termux.api.action.JAWN_BLE_STOP";
    private static final int NOTIFICATION_ID = 4202;
    private static final String CHANNEL_ID = "jawn-bt";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** Loopback TCP port; the client (tools/jawn-bt) connects to 127.0.0.1:PORT. */
    private static final int PORT = 45999;
    /** Where the last successful connect target is remembered for auto-connect. */
    private static final String PREFS = "jawn-bt";
    private int autoConnectAttempts = 0;

    private HandlerThread thread;
    private Handler handler;
    private ServerSocket serverSocket;
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<Socket>();
    private final Object writeLock = new Object();

    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private int mtu = 23;
    private int targetMtu = 0; // 0 = leave the link at the default 23 (MTU requests disturbed the T-Watch display)
    private boolean autoReconnect = false;
    private String targetMac;
    private UUID targetService, targetRx, targetTx;
    private long reconnectDelay = 3000;

    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<byte[]>();
    private boolean writing = false;

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    private final List<String> scanSeen = new ArrayList<String>();

    private boolean hasPerm(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean bluetoothReady() {
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) { emitError("no bluetooth adapter"); return false; }
        if (!adapter.isEnabled()) { emitError("bluetooth is disabled"); return false; }
        // The app targets SDK 28: on Android 12+ the legacy rules apply, where
        // BLE scan results require the location permission instead of
        // BLUETOOTH_SCAN. Trust targetSdkVersion rather than assume.
        if (getApplicationInfo().targetSdkVersion >= 31) {
            if (!hasPerm(android.Manifest.permission.BLUETOOTH_SCAN)) {
                emitError("missing Bluetooth scan permission; grant it via the JawnOS dashboard (Grant device permissions)");
                return false;
            }
        } else if (!hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            emitError("missing location permission (BLE scanning needs it on this app's target); grant it via the JawnOS dashboard (Grant device permissions)");
            return false;
        }
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "JawnOS BLE bridge", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = b.setContentTitle("JawnOS BLE bridge").setContentText("Bluetooth LE link running").setSmallIcon(R.drawable.jawn_notification).setOngoing(true).build();
        startForeground(NOTIFICATION_ID, n);

        thread = new HandlerThread("jawn-ble");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            // Loopback TCP: a filesystem unix socket created here was not usable
            // from the other app of the shared UID (app mount namespaces), and
            // Android LocalServerSocket defaults to SOCK_SEQPACKET which common
            // clients cannot speak. Loopback TCP has no such traps and matches
            // how jawn's own services listen.
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // Bind IPv4 loopback explicitly: getLoopbackAddress() can return
            // ::1 (IPv6), which is unreachable for IPv4 clients like jawn-bt.
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        } catch (Throwable t) {
            Logger.logError(LOG_TAG, "Failed to bind port " + PORT + ": " + t);
            stopSelf();
            return;
        }
        new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }).start();
        Logger.logInfo(LOG_TAG, "JawnBleService started on 127.0.0.1:" + PORT);
        // Hands-free receive: re-acquire the last device (e.g. the watch) so
        // its notifications flow without any send having to happen first.
        handler.postDelayed(new Runnable() {
            public void run() { autoConnectLast(); }
        }, 1500);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable t) { }
        for (Socket c : clients) { try { c.close(); } catch (Throwable t) { } }
        clients.clear();
        if (handler != null) {
            handler.post(new Runnable() {
                public void run() { closeGatt(); }
            });
        }
        if (thread != null) thread.quitSafely();
        Logger.logInfo(LOG_TAG, "JawnBleService stopped");
        super.onDestroy();
    }

    // ---------- socket plumbing ----------

    private void acceptLoop() {
        while (true) {
            try {
                Socket c = serverSocket.accept();
                clients.add(c);
                Logger.logInfo(LOG_TAG, "client connected, total " + clients.size());
                emitStatus(); // tell the new client where things stand
                new Thread(new clientReader(c)).start();
            } catch (Throwable t) {
                // socket closed or failed; if the service is dying, just exit
                if (serverSocket == null) return;
            }
        }
    }

    private class clientReader implements Runnable {
        private final Socket c;
        clientReader(Socket c) { this.c = c; }
        public void run() {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        final JSONObject o = new JSONObject(line);
                        handler.post(new Runnable() {
                            public void run() { handleCommand(o); }
                        });
                    } catch (Throwable t) {
                        emitError("bad command: " + t);
                    }
                }
            } catch (Throwable t) {
                Logger.logInfo(LOG_TAG, "client read ended: " + t);
            }
            clients.remove(c);
            Logger.logInfo(LOG_TAG, "client removed, total " + clients.size());
            try { c.close(); } catch (Throwable t) { }
        }
    }

    private void emit(JSONObject o) {
        String line = o.toString() + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        Logger.logInfo(LOG_TAG, "emit -> " + clients.size() + " client(s): " + (line.length() > 60 ? line.substring(0, 60) + "..." : line.trim()));
        synchronized (writeLock) {
            for (Socket c : clients) {
                try {
                    OutputStream os = c.getOutputStream();
                    os.write(bytes);
                    os.flush();
                } catch (Throwable t) {
                    Logger.logInfo(LOG_TAG, "emit failed: " + t);
                    clients.remove(c);
                    try { c.close(); } catch (Throwable t2) { }
                }
            }
        }
    }

    /** Emits from Bluetooth callbacks arrive on the main/binder thread; do the
     *  socket I/O on our handler thread. */
    private void emitFromCallback(final JSONObject o) {
        handler.post(new Runnable() {
            public void run() { emit(o); }
        });
    }

    private void emitError(String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "error");
            o.put("message", message);
            emit(o);
        } catch (Throwable t) { }
    }

    private void emitStatus() {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "status");
            o.put("connected", gatt != null && writeChar != null);
            o.put("mac", targetMac == null ? JSONObject.NULL : targetMac);
            o.put("mtu", mtu);
            emit(o);
        } catch (Throwable t) { }
    }

    // ---------- commands ----------

    private void handleCommand(JSONObject o) {
        String cmd = o.optString("cmd", "");
        Logger.logInfo(LOG_TAG, "cmd: " + cmd);
        if ("scan".equals(cmd)) {
            startScan(o.optInt("seconds", 8));
        } else if ("connect".equals(cmd)) {
            connect(o.optString("mac", null), o.optString("service", null), o.optString("rx", null), o.optString("tx", null), o.optInt("mtu", 0));
        } else if ("send".equals(cmd)) {
            sendText(o.optString("message", ""));
        } else if ("disconnect".equals(cmd)) {
            autoReconnect = false;
            if (gatt != null) gatt.disconnect();
            else emitStatus();
        } else if ("status".equals(cmd)) {
            emitStatus();
        } else if ("shutdown".equals(cmd)) {
            stopSelf();
        } else {
            emitError("unknown command: " + cmd);
        }
    }

    // ---------- scan ----------

    private final ScanCallback scanCallback = new ScanCallback() {
        public void onScanResult(int callbackType, ScanResult result) {
            try {
                String mac = result.getDevice().getAddress();
                if (scanSeen.contains(mac)) return;
                scanSeen.add(mac);
                JSONObject o = new JSONObject();
                o.put("event", "scan");
                o.put("mac", mac);
                String name = result.getDevice().getName();
                o.put("name", name == null ? JSONObject.NULL : name);
                o.put("rssi", result.getRssi());
                emitFromCallback(o);
            } catch (Throwable t) { }
        }
        public void onScanFailed(int errorCode) {
            emitError("scan failed: " + errorCode);
        }
    };

    private void startScan(int seconds) {
        if (!bluetoothReady()) return;
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emitError("scanner unavailable"); return; }
        scanSeen.clear();
        scanning = true;
        scanner.startScan(scanCallback);
        handler.postDelayed(new Runnable() {
            public void run() { stopScan(); }
        }, seconds * 1000L);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        Logger.logInfo(LOG_TAG, "scan finished, " + scanSeen.size() + " device(s)");
        try { scanner.stopScan(scanCallback); } catch (Throwable t) { }
        try {
            JSONObject o = new JSONObject();
            o.put("event", "scan_done");
            emit(o);
        } catch (Throwable t) { }
    }

    // ---------- gatt ----------

    private void connect(String mac, String service, String rx, String tx, int wantMtu) {
        if (!bluetoothReady()) return;
        if (mac == null || mac.isEmpty()) { emitError("connect: mac required"); return; }
        if (getApplicationInfo().targetSdkVersion >= 31 && !hasPerm(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            emitError("missing Bluetooth connect permission; use Grant device permissions in the JawnOS dashboard");
            return;
        }
        closeGatt();
        targetMac = mac;
        try {
            targetService = service == null || service.isEmpty() ? null : UUID.fromString(service);
            targetRx = rx == null || rx.isEmpty() ? null : UUID.fromString(rx);
            targetTx = tx == null || tx.isEmpty() ? null : UUID.fromString(tx);
        } catch (Throwable t) {
            emitError("bad uuid: " + t);
            return;
        }
        targetMtu = wantMtu;
        autoReconnect = true;
        reconnectDelay = 3000;
        rememberTarget();
        doConnect();
    }

    private void rememberTarget() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("mac", targetMac)
                .putString("service", targetService == null ? null : targetService.toString())
                .putString("rx", targetRx == null ? null : targetRx.toString())
                .putString("tx", targetTx == null ? null : targetTx.toString())
                .putInt("mtu", targetMtu)
                .apply();
        } catch (Throwable t) { }
    }

    private void autoConnectLast() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            String mac = sp.getString("mac", null);
            if (mac == null) return;
            if (gatt != null && writeChar != null) return; // already linked
            if (!bluetoothReady()) {
                if (autoConnectAttempts++ < 20) {
                    handler.postDelayed(new Runnable() {
                        public void run() { autoConnectLast(); }
                    }, 15000);
                }
                return;
            }
            Logger.logInfo(LOG_TAG, "auto-connecting to last device " + mac);
            connect(mac, sp.getString("service", null), sp.getString("rx", null), sp.getString("tx", null), sp.getInt("mtu", 512));
        } catch (Throwable t) {
            Logger.logInfo(LOG_TAG, "auto-connect failed: " + t);
        }
    }

    private void doConnect() {
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(targetMac);
            gatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { emitError("connectGatt returned null"); scheduleReconnect(); }
        } catch (Throwable t) {
            emitError("connect failed: " + t);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!autoReconnect) return;
        long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, 30000);
        handler.postDelayed(new Runnable() {
            public void run() {
                if (autoReconnect && gatt == null) doConnect();
            }
        }, delay);
    }

    private void closeGatt() {
        synchronized (writeQueue) { writeQueue.clear(); writing = false; }
        writeChar = null;
        notifyChar = null;
        if (gatt != null) {
            try { gatt.close(); } catch (Throwable t) { }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reconnectDelay = 3000;
                if (targetMtu > 0) {
                    // Off by default: requesting 512 disturbed the T-Watch's
                    // display rendering (BLE radio + LVGL flush contention).
                    // Pass "mtu" in the connect command to opt back in.
                    try { g.requestMtu(targetMtu); } catch (Throwable t) { }
                }
                try { g.discoverServices(); } catch (Throwable t) { emitError("discoverServices: " + t); }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt();
                try {
                    JSONObject o = new JSONObject();
                    o.put("event", "disconnected");
                    o.put("status", status);
                    emitFromCallback(o);
                } catch (Throwable t) { }
                scheduleReconnect();
            }
        }

        public void onMtuChanged(BluetoothGatt g, int newMtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu;
        }

        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) { emitError("service discovery failed: " + status); return; }
            writeChar = null;
            notifyChar = null;
            List<BluetoothGattService> services = g.getServices();
            for (BluetoothGattService s : services) {
                if (targetService != null && !s.getUuid().equals(targetService)) continue;
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    if (targetRx != null && c.getUuid().equals(targetRx)) writeChar = c;
                    if (targetTx != null && c.getUuid().equals(targetTx)) notifyChar = c;
                }
            }
            if (writeChar == null || notifyChar == null) {
                emitError("characteristics not found (service/rx/tx)");
                try { g.disconnect(); } catch (Throwable t) { }
                return;
            }
            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor d = notifyChar.getDescriptor(CCCD);
                if (d != null) {
                    d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(d);
                } else {
                    emitConnected();
                }
            } catch (Throwable t) {
                emitError("enable notifications: " + t);
            }
        }

        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (d.getUuid().equals(CCCD)) emitConnected();
        }

        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            try {
                if (notifyChar == null || !c.getUuid().equals(notifyChar.getUuid())) return;
                String text = new String(c.getValue(), StandardCharsets.UTF_8);
                JSONObject o = new JSONObject();
                o.put("event", "message");
                o.put("data", text);
                emitFromCallback(o);
            } catch (Throwable t) { }
        }

        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (writeQueue) { writing = false; }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitError("write failed: " + status);
                synchronized (writeQueue) { writeQueue.clear(); }
                emitSent();
                return;
            }
            pumpWrites();
        }
    };

    private void emitConnected() {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "connected");
            o.put("mac", targetMac);
            o.put("mtu", mtu);
            emitFromCallback(o);
        } catch (Throwable t) { }
    }

    private void sendText(String message) {
        if (gatt == null || writeChar == null) { emitError("not connected"); return; }
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        int chunk = Math.max(20, mtu - 3);
        synchronized (writeQueue) {
            for (int i = 0; i < bytes.length; i += chunk) {
                int len = Math.min(chunk, bytes.length - i);
                byte[] part = new byte[len];
                System.arraycopy(bytes, i, part, 0, len);
                writeQueue.add(part);
            }
        }
        pumpWrites();
    }

    private void pumpWrites() {
        byte[] next;
        synchronized (writeQueue) {
            if (writing) return;
            next = writeQueue.poll();
            if (next == null) { emitSent(); return; }
            writing = true;
        }
        try {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writeChar.setValue(next);
            boolean ok = gatt.writeCharacteristic(writeChar);
            if (!ok) {
                synchronized (writeQueue) { writing = false; writeQueue.clear(); }
                emitError("writeCharacteristic returned false");
                emitSent();
            }
        } catch (Throwable t) {
            synchronized (writeQueue) { writing = false; writeQueue.clear(); }
            emitError("write: " + t);
            emitSent();
        }
    }

    private void emitSent() {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "sent");
            emit(o);
        } catch (Throwable t) { }
    }
}
