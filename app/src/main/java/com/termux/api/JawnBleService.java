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
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.logger.Logger;

/**
 * jawn-bt daemon: a persistent Bluetooth LE (Nordic UART Service) bridge for
 * jawn, replacing the Tasker + BLE-plugin middleman. Listens on a loopback TCP
 * socket at 127.0.0.1:45999 (same shared UID, so jawn connects directly), and
 * speaks the v2 multi-link protocol: up to four independent GATT links, each
 * with its own reconnect backoff, write queue and receive reassembly buffer.
 *
 * Every command is a newline-delimited JSON object; every event names the link
 * it belongs to (mac) so a single daemon can serve a watch, a deck and boards
 * at the same time. The wire format matches the desktop daemon
 * (desktop/jawnos-bt-daemon) so scripts/jawn-bt works unchanged.
 */
public class JawnBleService extends Service {

    private static final String LOG_TAG = "JawnBleService";
    public static final String ACTION_STOP = "com.termux.api.action.JAWN_BLE_STOP";
    private static final int NOTIFICATION_ID = 4202;
    private static final String CHANNEL_ID = "jawn-bt";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** Loopback TCP port; the client (tools/jawn-bt) connects to 127.0.0.1:PORT. */
    private static final int PORT = 45999;

    /** The Nordic UART service. connect may omit the UUIDs; these are the defaults. */
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    private static final String PREFS = "jawn-bt";
    /** v2 target store: a JSON object keyed by uppercase MAC. */
    private static final String KEY_TARGETS = "targets";
    /** Legacy flat keys, migrated once into KEY_TARGETS. */
    private static final String LEGACY_MAC = "mac";
    private static final String LEGACY_SERVICE = "service";
    private static final String LEGACY_RX = "rx";
    private static final String LEGACY_TX = "tx";
    private static final String LEGACY_MTU = "mtu";

    private static final int DEFAULT_MAX_LINKS = 4;
    private static final int MAX_LINKS_CEILING = 25;
    private static final String KEY_MAX_LINKS = "max_links";
    /** How many radios this bridge may hold at once. Four is the safe default;
     *  a preference (or JAWNOS_BLE_MAX_LINKS) may raise it to 25. */
    private int maxLinks = DEFAULT_MAX_LINKS;
    private static final String STATE_DISCONNECTED = "disconnected";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_CONNECTED = "connected";

    /** A connectGatt() attempt that never reports back leaves a client stuck
     *  "connecting" forever and every retry no-ops on it. Android's BLE stack
     *  can swallow the callback when the peripheral vanishes mid-connect, and
     *  each stuck client leaks until the adapter refuses to connect at all
     *  (bluetooth "just stops working" until the phone is restarted). */
    private static final long CONNECT_TIMEOUT_MS = 20000;
    /** Only a link that stood this long is a real link. A drop sooner is a flap
     *  (the watch rebooted, or its radio slept): the reconnect delay keeps
     *  doubling instead of resetting, so a connect-then-drop loop cannot retry
     *  every 3 s for an hour and cook the phone. */
    private static final long STABLE_LINK_MS = 30000;
    private static final long RECONNECT_START_MS = 3000;
    private static final long RECONNECT_MAX_MS = 30000;
    /** A background (autoConnect) request may wait this long for the watch to
     *  advertise before it is re-issued. The radio stays asleep until then. */
    private static final long SCREEN_OFF_CONNECT_TIMEOUT_MS = 5 * 60 * 1000;
    /** A pause this long means a new message, not a lost part. */
    private static final long RX_GAP_MS = 5000;
    /** A runaway total cannot grow the buffer without bound. */
    private static final int RX_MAX_PARTS = 4096;
    /** Longest "<i>/<n>:" prefix the framing maths budgets for. */
    private static final int FRAME_HEADER_ROOM = 12;
    /** How long one part may sit unacknowledged before the link is treated as
     *  wedged. A watch that suspends its radio never acks a write, and Android
     *  only reports the drop when the link supervision timeout expires (20 s or
     *  more), so every message queued behind that write waited out the whole
     *  timeout and the queue grew with each one. Recycle the link instead: the
     *  pending message fails at once and the reconnect path restores the radio
     *  when the watch wakes. Comfortably above a healthy write - a whole room
     *  push is a couple of seconds even at mtu 23. */
    private static final long WRITE_ACK_TIMEOUT_MS = 8000;
    /** Cap on one link's unsent queue, in bytes. Sends are fire-and-forget, so
     *  nothing above them stops a sleeping watch accumulating messages. A
     *  message is all-or-nothing (its parts are framed with a total), so
     *  overflow drops the queue rather than deliver a message missing its
     *  middle. */
    private static final int WRITE_QUEUE_MAX_BYTES = 64 * 1024;

    private int autoConnectAttempts = 0;
    /** Whether the phone is interactive. With the screen off a reconnect is a
     *  background request, which costs no radio until the watch advertises. */
    private volatile boolean screenOn = true;
    private BroadcastReceiver screenReceiver;
    /** Incremented on every successful announce, so "most recently connected"
     *  is exact even when two links finish in the same millisecond. */
    private long connectSeq = 0;

    private HandlerThread thread;
    private Handler handler;
    private ServerSocket serverSocket;
    /** Cleared from onDestroy so acceptLoop() can leave. Without it a closed
     *  serverSocket makes accept() throw "Socket closed" forever and the loop
     *  spins at 100% CPU (and churns a SocketException per iteration). */
    private volatile boolean running = true;
    private final CopyOnWriteArrayList<Socket> clients = new CopyOnWriteArrayList<Socket>();
    private final Object writeLock = new Object();

    private BluetoothAdapter adapter;

    /** Handler-thread owned. Insertion order is first-seen order. */
    private final LinkedHashMap<String, Link> links = new LinkedHashMap<String, Link>();
    /** Handler-thread owned; routes a callback (which may arrive on a binder
     *  thread) to the link that owns that BluetoothGatt. */
    private final HashMap<BluetoothGatt, Link> byGatt = new HashMap<BluetoothGatt, Link>();

    private boolean targetsRestored = false;
    /** Handler-thread owned desired links, persisted as the KEY_TARGETS map.
     *  Kept separate from links so a target that has not connected yet (or
     *  whose restore attempt failed) is not erased by the next persist. */
    private final LinkedHashMap<String, Target> targets = new LinkedHashMap<String, Target>();

    private BluetoothLeScanner scanner;
    private boolean scanning = false;
    /** Handler-thread owned (scan results are posted onto it). */
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
        maxLinks = configuredMaxLinks();
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

        // While the screen is off the watch decides when it is reachable (a
        // wrist tilt wakes it), so the link waits in the background instead of
        // waking the radio on every retry. Screen-on promotes it at once.
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            screenOn = pm == null || pm.isInteractive();
            screenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                    if (on && !screenOn) {
                        screenOn = true;
                        handler.post(new Runnable() {
                            public void run() { promoteLinks(); }
                        });
                    }
                    else if (!on) { screenOn = false; }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, filter);
        } catch (Throwable t) {
            screenOn = true;
        }

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
        // Hands-free receive: re-acquire every remembered device (the watch, a
        // deck, any board) so their notifications flow without a send first.
        handler.postDelayed(new Runnable() {
            public void run() { autoConnectAll(); }
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
        running = false;
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Throwable t) { }
            screenReceiver = null;
        }
        try { if (serverSocket != null) serverSocket.close(); } catch (Throwable t) { }
        for (Socket c : clients) { try { c.close(); } catch (Throwable t) { } }
        clients.clear();
        if (handler != null) {
            // Queued before quitSafely(), so it runs; every link is closed.
            handler.post(new Runnable() {
                public void run() { closeAllLinks(); }
            });
        }
        if (thread != null) thread.quitSafely();
        Logger.logInfo(LOG_TAG, "JawnBleService stopped");
        super.onDestroy();
    }

    // ---------- socket plumbing ----------

    private void acceptLoop() {
        while (running) {
            try {
                Socket c = serverSocket.accept();
                clients.add(c);
                Logger.logInfo(LOG_TAG, "client connected, total " + clients.size());
                // tell the new client where things stand; links are handler-owned
                handler.post(new Runnable() {
                    public void run() { emitStatus(); }
                });
                new Thread(new clientReader(c)).start();
            } catch (Throwable t) {
                // A closed socket makes accept() throw "Socket closed" every
                // time: leaving must be explicit. The old test checked a field
                // that was never nulled, so a Stop/Start of the bridge leaked a
                // thread that spun at 100% CPU (churning allocations and
                // burning battery). If we are still meant to run, back off
                // briefly instead of hot-looping.
                if (!running || serverSocket == null || serverSocket.isClosed()) return;
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
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

    private void emitError(String message) {
        emitError(message, null, null);
    }

    /** An error names the link when it is known, and may carry a stable code
     *  (e.g. "too_many_links") for clients that do not want to parse prose. */
    private void emitError(String message, String mac, String code) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "error");
            o.put("message", message);
            if (mac != null) o.put("mac", mac);
            if (code != null) o.put("code", code);
            emit(o);
        } catch (Throwable t) { }
    }

    private void emitStatus() {
        try {
            Link primary = primaryLink();
            JSONArray arr = new JSONArray();
            long now = System.currentTimeMillis();
            for (Link l : links.values()) {
                JSONObject e = new JSONObject();
                e.put("mac", l.mac);
                e.put("state", l.state);
                e.put("connected", isConnected(l));
                e.put("mtu", l.mtu);
                e.put("service", l.service.toString());
                e.put("rx", l.rx.toString());
                e.put("tx", l.tx.toString());
                e.put("since", l.since);
                e.put("attempt", l.attempt);
                e.put("next_retry_ms", l.nextRetryAt > now ? (l.nextRetryAt - now) : 0);
                arr.put(e);
            }
            JSONObject o = new JSONObject();
            o.put("event", "status");
            o.put("protocol", 2);
            o.put("connected", primary != null);
            o.put("mac", primary == null ? JSONObject.NULL : primary.mac);
            // Keep mtu numeric even with no primary; 23 is the BLE default.
            o.put("mtu", primary == null ? 23 : primary.mtu);
            o.put("count", links.size());
            o.put("links", arr);
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
            connect(o.optString("mac", null), o.optString("service", null),
                    o.optString("rx", null), o.optString("tx", null),
                    o.optInt("mtu", 0), o.optBoolean("compress", true));
        } else if ("send".equals(cmd)) {
            Integer id = null;
            if (o.has("id") && !o.isNull("id")) id = Integer.valueOf(o.optInt("id"));
            sendText(o.optString("message", ""), o.optString("mac", null), id);
        } else if ("disconnect".equals(cmd)) {
            disconnect(o);
        } else if ("status".equals(cmd)) {
            emitStatus();
        } else if ("shutdown".equals(cmd)) {
            stopSelf();
        } else {
            emitError("unknown command: " + cmd);
        }
    }

    private int configuredMaxLinks() {
        int n = DEFAULT_MAX_LINKS;
        String env = System.getenv("JAWNOS_BLE_MAX_LINKS");
        if (env != null) {
            try { n = Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) { }
        }
        // A preference on the bridge's own store wins over the environment,
        // which Android apps cannot normally set.
        n = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_MAX_LINKS, n);
        if (n < 1) n = 1;
        if (n > MAX_LINKS_CEILING) n = MAX_LINKS_CEILING;
        Logger.logInfo(LOG_TAG, "max links: " + n);
        return n;
    }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    // ---------- scan ----------

    private final ScanCallback scanCallback = new ScanCallback() {
        public void onScanResult(int callbackType, ScanResult result) {
            String mac;
            String name;
            int rssi;
            ArrayList<String> uuids = new ArrayList<String>();
            JSONObject manufacturer = new JSONObject();
            try {
                mac = result.getDevice().getAddress();
                name = result.getDevice().getName();
                rssi = result.getRssi();
                ScanRecord rec = result.getScanRecord();
                if (rec != null) {
                    List<ParcelUuid> advertised = rec.getServiceUuids();
                    if (advertised != null) {
                        for (ParcelUuid p : advertised) {
                            if (p != null && p.getUuid() != null) uuids.add(p.getUuid().toString());
                        }
                    }
                    // JawnOS devices carry \"JW\" manufacturer data (company
                    // 0xFFFF) with their chip id and purpose, so the manager can
                    // recognize one even when it no longer advertises NUS.
                    SparseArray<byte[]> specific = rec.getManufacturerSpecificData();
                    if (specific != null) {
                        for (int i = 0; i < specific.size(); i++) {
                            byte[] payload = specific.valueAt(i);
                            if (payload != null && payload.length > 0) {
                                manufacturer.put(String.valueOf(specific.keyAt(i)), hex(payload));
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                return;
            }
            final String fMac = mac;
            final String fName = name;
            final int fRssi = rssi;
            final ArrayList<String> fUuids = uuids;
            final JSONObject fManufacturer = manufacturer;
            handler.post(new Runnable() {
                public void run() { handleScanResult(fMac, fName, fRssi, fUuids, fManufacturer); }
            });
        }
        public void onScanFailed(final int errorCode) {
            handler.post(new Runnable() {
                public void run() { emitError("scan failed: " + errorCode); }
            });
        }
    };

    private void handleScanResult(String mac, String name, int rssi, List<String> uuids, JSONObject manufacturer) {
        try {
            if (!scanning) return;
            if (scanSeen.contains(mac)) return;
            scanSeen.add(mac);
            JSONObject o = new JSONObject();
            o.put("event", "scan");
            o.put("mac", mac);
            o.put("name", name == null ? JSONObject.NULL : name);
            o.put("rssi", rssi);
            // The Perl adopter identifies a JawnOS board by the Nordic UART
            // service in its advertising packet; without this it cannot work.
            JSONArray arr = new JSONArray();
            if (uuids != null) for (String u : uuids) arr.put(u);
            o.put("uuids", arr);
            o.put("manufacturer_data", manufacturer == null ? new JSONObject() : manufacturer);
            emit(o);
        } catch (Throwable t) { }
    }

    private void startScan(int seconds) {
        if (!bluetoothReady()) return;
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { emitError("scanner unavailable"); return; }
        scanSeen.clear();
        scanning = true;
        try {
            scanner.startScan(scanCallback);
        } catch (Throwable t) {
            scanning = false;
            emitError("scan: " + t);
            return;
        }
        handler.postDelayed(new Runnable() {
            public void run() { stopScan(); }
        }, Math.max(1, seconds) * 1000L);
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

    // ---------- link model ----------

    private static final class Target {
        final String mac;
        final String service;
        final String rx;
        final String tx;
        final int mtu;
        final boolean compress;
        Target(String mac, String service, String rx, String tx, int mtu, boolean compress) {
            this.mac = mac;
            this.service = service;
            this.rx = rx;
            this.tx = tx;
            this.mtu = mtu;
            this.compress = compress;
        }
    }

    private static final class WritePart {
        final byte[] bytes;
        final boolean endOfMessage;
        /** The send command's id, repeated on every part so a failure can name it. */
        final Integer id;
        WritePart(byte[] bytes, boolean endOfMessage, Integer id) {
            this.bytes = bytes;
            this.endOfMessage = endOfMessage;
            this.id = id;
        }
    }

    /** One peripheral. Every field is handler-thread owned. */
    private final class Link {
        final String mac;
        UUID service;
        UUID rx;
        UUID tx;
        int mtu = 23;
        int targetMtu;
        boolean compress;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic writeChar;
        BluetoothGattCharacteristic notifyChar;
        boolean autoReconnect = true;
        boolean connecting = false;
        /** This attempt is a background (autoConnect) request, not an active one. */
        boolean background = false;
        boolean announced = false;
        long connectedAt = 0;
        long connectStartedAt = 0;
        long connectSeq = 0;
        long since = 0;
        long reconnectDelay = RECONNECT_START_MS;
        long nextRetryAt = 0;
        int attempt = 0;
        String state = STATE_DISCONNECTED;

        final ArrayDeque<WritePart> writeQueue = new ArrayDeque<WritePart>();
        boolean writing = false;
        WritePart currentPart = null;
        /** Bytes sitting in writeQueue (not the part in flight). */
        int writeQueueBytes = 0;

        ArrayList<byte[]> rxParts = null;
        int rxTotal = 0;
        long rxAt = 0;

        final Runnable connectTimeout;
        final Runnable reconnectRunner;
        final Runnable writeTimeout;

        Link(String mac, UUID service, UUID rx, UUID tx, int targetMtu, boolean compress) {
            this.mac = mac;
            this.service = service;
            this.rx = rx;
            this.tx = tx;
            this.targetMtu = targetMtu;
            this.compress = compress;
            this.since = System.currentTimeMillis();
            this.connectTimeout = new Runnable() {
                public void run() { connectTimedOut(Link.this); }
            };
            this.reconnectRunner = new Runnable() {
                public void run() { reconnectNow(Link.this); }
            };
            this.writeTimeout = new Runnable() {
                public void run() { writeTimedOut(Link.this); }
            };
        }

        boolean matches(UUID service, UUID rx, UUID tx, int targetMtu, boolean compress) {
            return this.service.equals(service)
                    && this.rx.equals(rx)
                    && this.tx.equals(tx)
                    && this.targetMtu == targetMtu
                    && this.compress == compress;
        }
    }

    private boolean isConnected(Link link) {
        return link != null && link.gatt != null && link.writeChar != null
                && link.notifyChar != null && STATE_CONNECTED.equals(link.state);
    }

    /** The primary link is the most recently connected one. */
    private Link primaryLink() {
        Link best = null;
        for (Link l : links.values()) {
            if (!isConnected(l)) continue;
            if (best == null || l.connectSeq > best.connectSeq) best = l;
        }
        return best;
    }

    private static String normalizeMac(String mac) {
        if (mac == null) return null;
        String text = mac.trim().toUpperCase(Locale.US).replaceAll("[^0-9A-F]", "");
        if (text.length() == 12) {
            StringBuilder out = new StringBuilder(17);
            for (int i = 0; i < 12; i += 2) {
                if (i > 0) out.append(':');
                out.append(text, i, i + 2);
            }
            return out.toString();
        }
        return mac.trim().toUpperCase(Locale.US);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return UUID.fromString(value.trim());
    }

    private static UUID storedUuid(String value, UUID fallback) {
        try {
            UUID u = parseUuid(value);
            return u == null ? fallback : u;
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---------- connect / disconnect ----------

    private void connect(String macRaw, String serviceS, String rxS, String txS,
                         int wantMtu, boolean compress) {
        if (!bluetoothReady()) return;
        if (macRaw == null || macRaw.trim().isEmpty()) {
            emitError("connect: mac required");
            return;
        }
        final String mac = normalizeMac(macRaw);
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
            emitError("connect: invalid mac: " + macRaw, mac, null);
            return;
        }
        if (getApplicationInfo().targetSdkVersion >= 31
                && !hasPerm(android.Manifest.permission.BLUETOOTH_CONNECT)) {
            emitError("missing Bluetooth connect permission; use Grant device permissions in the JawnOS dashboard", mac, null);
            return;
        }

        UUID service, rx, tx;
        try {
            service = parseUuid(serviceS);
            rx = parseUuid(rxS);
            tx = parseUuid(txS);
        } catch (Throwable t) {
            emitError("bad uuid: " + t.getMessage(), mac, null);
            return;
        }
        if (service == null) service = NUS_SERVICE;
        if (rx == null) rx = NUS_RX;
        if (tx == null) tx = NUS_TX;
        final int targetMtu = Math.max(0, wantMtu);

        // Load the durable set before the first upsert: an explicit connect
        // that races the startup restore must not overwrite targets it has
        // not seen yet.
        restoreTargetsIfNeeded();

        // targets is the durable desired set; links is only what is live
        // (connected, connecting, or waiting to retry). Keeping them separate
        // means a failed restore attempt cannot erase the other targets.
        Link existing = links.get(mac);
        if (existing != null && existing.matches(service, rx, tx, targetMtu, compress)) {
            // Idempotent: a second connect for a target already linked (or
            // mid-connect) must not tear the first attempt down.
            if (isConnected(existing) || existing.connecting || existing.gatt != null) {
                emitStatus();
                return;
            }
            targets.put(mac, new Target(mac, service.toString(), rx.toString(), tx.toString(), targetMtu, compress));
            existing.autoReconnect = true;
            existing.reconnectDelay = RECONNECT_START_MS;
            existing.attempt = 0;
            persistTargets();
            doConnect(existing, false);
            return;
        }
        if (existing != null) {
            // Upsert only this MAC: reconfigure it in place, leaving the other
            // links alone.
            existing.autoReconnect = false;
            releaseGatt(existing);
            links.remove(mac);
            emitDisconnected(existing, 0, "reconfigured", 0);
        }
        if (!targets.containsKey(mac) && targets.size() >= maxLinks) {
            emitError("too many links (max " + maxLinks + ")", mac, "too_many_links");
            return;
        }
        if (links.size() >= maxLinks) {
            emitError("too many links (max " + maxLinks + ")", mac, "too_many_links");
            return;
        }

        targets.put(mac, new Target(mac, service.toString(), rx.toString(), tx.toString(), targetMtu, compress));
        Link link = new Link(mac, service, rx, tx, targetMtu, compress);
        links.put(mac, link);
        persistTargets();
        doConnect(link, false);
    }

    private void disconnect(JSONObject o) {
        restoreTargetsIfNeeded();
        boolean all = o.optBoolean("all", false);
        String macRaw = o.optString("mac", null);
        // Legacy: no mac means "all links".
        if (!all && (macRaw == null || macRaw.trim().isEmpty())) all = true;

        if (all) {
            // Forget the durable set too: a disconnect must not be undone by
            // the next service start. This also covers a disconnect that
            // arrives before the startup restore has created any links.
            targets.clear();
            ArrayList<Link> snapshot = new ArrayList<Link>(links.values());
            if (snapshot.isEmpty()) { persistTargets(); emitStatus(); return; }
            for (Link link : snapshot) {
                link.autoReconnect = false;
                releaseGatt(link);
                links.remove(link.mac);
                emitDisconnected(link, 0, "requested", 0);
            }
            persistTargets();
            return;
        }

        final String mac = normalizeMac(macRaw);
        Link link = links.get(mac);
        if (link == null) {
            // A target loaded at startup but not yet connected still counts as
            // desired; disconnect must cancel it too.
            targets.remove(mac);
            persistTargets();
            emitStatus();
            return;
        }
        link.autoReconnect = false;
        releaseGatt(link);
        links.remove(mac);
        targets.remove(mac);
        persistTargets();
        emitDisconnected(link, 0, "requested", 0);
    }

    private void doConnect(Link link, boolean background) {
        if (link.gatt != null || link.connecting) return;
        handler.removeCallbacks(link.reconnectRunner);
        handler.removeCallbacks(link.connectTimeout);
        link.nextRetryAt = 0;
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(link.mac);
            link.connecting = true;
            link.state = STATE_CONNECTING;
            link.connectStartedAt = System.currentTimeMillis();
            link.since = link.connectStartedAt;
            link.attempt++;
            // With the screen off, an active attempt wakes the radio just to fail
            // while the watch sleeps. A background request waits for the
            // peripheral to advertise - a wrist tilt - and costs nothing until
            // then, so "hey jawn" is still met the moment the watch wakes.
            boolean bg = background && !screenOn;
            link.background = bg;
            BluetoothGatt g = dev.connectGatt(this, bg, gattCallback, BluetoothDevice.TRANSPORT_LE);
            if (g == null) {
                link.connecting = false;
                link.state = STATE_DISCONNECTED;
                link.since = System.currentTimeMillis();
                emitError("connectGatt returned null", link.mac, null);
                scheduleReconnect(link);
                return;
            }
            link.gatt = g;
            byGatt.put(g, link);
            handler.postDelayed(link.connectTimeout,
                (bg ? SCREEN_OFF_CONNECT_TIMEOUT_MS : CONNECT_TIMEOUT_MS) + 1000);
        } catch (Throwable t) {
            link.connecting = false;
            link.state = STATE_DISCONNECTED;
            link.since = System.currentTimeMillis();
            emitError("connect failed: " + t, link.mac, null);
            scheduleReconnect(link);
        }
    }

    /** The screen came on: replace a waiting background request with an active
     *  connect, so the watch is reachable the moment the person looks. */
    private void promoteLinks() {
        if (!running) return;
        for (Link link : new ArrayList<Link>(links.values())) {
            if (!link.background || link.gatt == null || isConnected(link)) continue;
            Logger.logInfo(LOG_TAG, "screen on: promoting link " + link.mac);
            releaseGatt(link);
            doConnect(link, false);
        }
    }

    private void connectTimedOut(Link link) {
        if (!link.connecting) return;
        Logger.logInfo(LOG_TAG, "connect attempt timed out for " + link.mac + "; closing and retrying");
        failLink(link, 0, "connect timeout");
    }

    private void reconnectNow(Link link) {
        link.nextRetryAt = 0;
        if (!running) return;
        if (link.autoReconnect && link.gatt == null && !link.connecting) doConnect(link, true);
    }

    /** Returns the delay in milliseconds, which is what the disconnected event
     *  reports as next_retry_ms. */
    private long scheduleReconnect(Link link) {
        if (!link.autoReconnect) { link.nextRetryAt = 0; return 0; }
        long delay = link.reconnectDelay;
        link.reconnectDelay = Math.min(link.reconnectDelay * 2, RECONNECT_MAX_MS);
        link.nextRetryAt = System.currentTimeMillis() + delay;
        // one pending retry at a time: a second schedule moves the attempt
        // instead of racing two connects against each other
        handler.removeCallbacks(link.reconnectRunner);
        handler.postDelayed(link.reconnectRunner, delay);
        return delay;
    }

    /** Close only this link's GATT client and clear its per-link state. */
    private void releaseGatt(Link link) {
        handler.removeCallbacks(link.connectTimeout);
        handler.removeCallbacks(link.reconnectRunner);
        handler.removeCallbacks(link.writeTimeout);
        link.nextRetryAt = 0;
        link.connecting = false;
        link.background = false;
        link.connectedAt = 0;
        link.announced = false;
        link.state = STATE_DISCONNECTED;
        link.since = System.currentTimeMillis();
        link.mtu = 23;
        link.writeQueue.clear();
        link.writeQueueBytes = 0;
        link.writing = false;
        link.currentPart = null;
        resetRx(link);
        link.writeChar = null;
        link.notifyChar = null;
        BluetoothGatt g = link.gatt;
        link.gatt = null;
        if (g != null) {
            byGatt.remove(g);
            try { g.close(); } catch (Throwable t) { }
        }
    }

    private void failLink(Link link, int status, String reason) {
        releaseGatt(link);
        long delay = link.autoReconnect ? scheduleReconnect(link) : 0;
        emitDisconnected(link, status, reason, delay);
    }

    private void closeAllLinks() {
        for (Link link : new ArrayList<Link>(links.values())) {
            link.autoReconnect = false;
            releaseGatt(link);
        }
        links.clear();
        byGatt.clear();
    }

    // ---------- persistence ----------

    private void persistTargets() {
        try {
            JSONObject map = new JSONObject();
            int n = 0;
            for (Target target : targets.values()) {
                if (n++ >= maxLinks) break;
                JSONObject t = new JSONObject();
                t.put("service", target.service);
                t.put("rx", target.rx);
                t.put("tx", target.tx);
                t.put("mtu", target.mtu);
                // Protocol parity: the desktop daemon can zlib-compress large
                // payloads. Android stores the flag but still sends plain
                // UTF-8, which every peripheral already understands.
                t.put("compress", target.compress);
                map.put(target.mac, t);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_TARGETS, map.toString())
                    .apply();
        } catch (Throwable t) {
            Logger.logInfo(LOG_TAG, "persist targets failed: " + t);
        }
    }

    private JSONObject loadTargetMap() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            if (!sp.contains(KEY_TARGETS)) {
                String oldMac = sp.getString(LEGACY_MAC, null);
                JSONObject map = new JSONObject();
                if (oldMac != null && !oldMac.trim().isEmpty()) {
                    JSONObject t = new JSONObject();
                    t.put("service", legacyValue(sp.getString(LEGACY_SERVICE, null), NUS_SERVICE.toString()));
                    t.put("rx", legacyValue(sp.getString(LEGACY_RX, null), NUS_RX.toString()));
                    t.put("tx", legacyValue(sp.getString(LEGACY_TX, null), NUS_TX.toString()));
                    t.put("mtu", Math.max(0, sp.getInt(LEGACY_MTU, 0)));
                    t.put("compress", true);
                    map.put(normalizeMac(oldMac), t);
                }
                sp.edit().putString(KEY_TARGETS, map.toString())
                        .remove(LEGACY_MAC).remove(LEGACY_SERVICE)
                        .remove(LEGACY_RX).remove(LEGACY_TX).remove(LEGACY_MTU)
                        .apply();
            }
            String raw = sp.getString(KEY_TARGETS, null);
            return raw == null ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable t) {
            Logger.logInfo(LOG_TAG, "target load failed: " + t);
            return new JSONObject();
        }
    }

    private static String legacyValue(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void restoreTargetsIfNeeded() {
        if (targetsRestored) return;
        targetsRestored = true;
        JSONObject map = loadTargetMap();
        Iterator<String> keys = map.keys();
        while (keys.hasNext() && targets.size() < maxLinks) {
            String rawMac = keys.next();
            String mac = normalizeMac(rawMac);
            if (mac == null || !BluetoothAdapter.checkBluetoothAddress(mac)) continue;
            JSONObject t = map.optJSONObject(rawMac);
            if (t == null) continue;
            UUID service = storedUuid(t.optString("service", null), NUS_SERVICE);
            UUID rx = storedUuid(t.optString("rx", null), NUS_RX);
            UUID tx = storedUuid(t.optString("tx", null), NUS_TX);
            int mtu = Math.max(0, t.optInt("mtu", 0));
            boolean compress = t.optBoolean("compress", true);
            targets.put(mac, new Target(mac, service.toString(), rx.toString(), tx.toString(), mtu, compress));
        }
    }

    private void autoConnectAll() {
        if (!running) return;
        restoreTargetsIfNeeded();
        if (targets.isEmpty()) return;
        if (!bluetoothReady()) {
            if (autoConnectAttempts++ < 20) {
                handler.postDelayed(new Runnable() {
                    public void run() { autoConnectAll(); }
                }, 15000);
            }
            return;
        }
        autoConnectAttempts = 0;
        ArrayList<Target> wanted = new ArrayList<Target>(targets.values());
        for (Target t : wanted) {
            if (links.containsKey(t.mac)) continue;
            if (links.size() >= maxLinks) break;
            Logger.logInfo(LOG_TAG, "auto-connecting to " + t.mac);
            connect(t.mac, t.service, t.rx, t.tx, t.mtu, t.compress);
        }
    }

    // ---------- gatt callbacks ----------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            handler.post(new Runnable() {
                public void run() { handleConnectionState(g, status, newState); }
            });
        }

        public void onMtuChanged(final BluetoothGatt g, final int newMtu, final int status) {
            handler.post(new Runnable() {
                public void run() { handleMtuChanged(g, newMtu, status); }
            });
        }

        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            handler.post(new Runnable() {
                public void run() { handleServicesDiscovered(g, status); }
            });
        }

        public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
            final UUID uuid;
            try { uuid = d == null ? null : d.getUuid(); } catch (Throwable t) { return; }
            handler.post(new Runnable() {
                public void run() { handleDescriptorWrite(g, uuid, status); }
            });
        }

        public void onCharacteristicChanged(final BluetoothGatt g, BluetoothGattCharacteristic c) {
            // Copy the bytes synchronously: the stack reuses the value buffer
            // as soon as this callback returns.
            final UUID uuid;
            final byte[] value;
            try {
                uuid = c.getUuid();
                byte[] raw = c.getValue();
                value = raw == null ? new byte[0] : raw.clone();
            } catch (Throwable t) {
                return;
            }
            handler.post(new Runnable() {
                public void run() { handleNotification(g, uuid, value); }
            });
        }

        public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
            handler.post(new Runnable() {
                public void run() { handleCharacteristicWrite(g, status); }
            });
        }
    };

    private void handleConnectionState(BluetoothGatt g, int status, int newState) {
        Link link = byGatt.get(g);
        if (link == null || link.gatt != g) {
            // A callback from a client that has already been replaced (a
            // connect that timed out, then a fresh one): close it, so the
            // adapter's client table does not grow a dead entry per flap.
            try { g.close(); } catch (Throwable t) { }
            return;
        }
        long now = System.currentTimeMillis();
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            link.connecting = false;
            link.connectedAt = now;
            link.since = now;
            // Do not reset reconnectDelay here: a peripheral that connects and
            // drops again in seconds is a flap, and its delay must keep
            // doubling (STABLE_LINK_MS is the threshold for a real link).
            link.nextRetryAt = 0;
            handler.removeCallbacks(link.connectTimeout);
            if (link.targetMtu > 0) {
                // Off by default: requesting 512 disturbed the T-Watch's
                // display rendering (BLE radio + LVGL flush contention).
                // Pass "mtu" in the connect command to opt back in.
                try { g.requestMtu(link.targetMtu); } catch (Throwable t) { }
            }
            try {
                if (!g.discoverServices()) {
                    emitError("discoverServices returned false", link.mac, null);
                    failLink(link, 0, "service discovery failed");
                }
            } catch (Throwable t) {
                emitError("discoverServices: " + t, link.mac, null);
                failLink(link, 0, "service discovery failed");
            }
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED
                || newState == BluetoothProfile.STATE_CONNECTED) {
            long upFor = link.connectedAt == 0 ? 0 : now - link.connectedAt;
            if (upFor >= STABLE_LINK_MS) {
                link.reconnectDelay = RECONNECT_START_MS; // a real link dropped: retry promptly
            }
            // else a flap (the watch sleeping or rebooting): leave the delay
            // doubling, so the retry cadence backs off to 30 s.
            String reason = status == BluetoothGatt.GATT_SUCCESS ? "peer" : ("gatt status " + status);
            failLink(link, status, reason);
        }
    }

    private void handleMtuChanged(BluetoothGatt g, int newMtu, int status) {
        Link link = byGatt.get(g);
        if (link == null || link.gatt != g) return;
        if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= 23) link.mtu = newMtu;
    }

    private void handleServicesDiscovered(BluetoothGatt g, int status) {
        Link link = byGatt.get(g);
        if (link == null || link.gatt != g) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitError("service discovery failed: " + status, link.mac, null);
            failLink(link, status, "service discovery failed");
            return;
        }
        link.writeChar = null;
        link.notifyChar = null;
        for (BluetoothGattService s : g.getServices()) {
            if (!s.getUuid().equals(link.service)) continue;
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (c.getUuid().equals(link.rx)) link.writeChar = c;
                if (c.getUuid().equals(link.tx)) link.notifyChar = c;
            }
        }
        if (link.writeChar == null || link.notifyChar == null) {
            emitError("characteristics not found (service/rx/tx)", link.mac, null);
            failLink(link, 0, "characteristics not found");
            return;
        }
        try {
            if (!g.setCharacteristicNotification(link.notifyChar, true)) {
                emitError("setCharacteristicNotification returned false", link.mac, null);
                failLink(link, 0, "enable notifications failed");
                return;
            }
            BluetoothGattDescriptor d = link.notifyChar.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!g.writeDescriptor(d)) {
                    emitError("writeDescriptor returned false", link.mac, null);
                    failLink(link, 0, "enable notifications failed");
                }
            } else {
                announceConnected(link);
            }
        } catch (Throwable t) {
            emitError("enable notifications: " + t, link.mac, null);
            failLink(link, 0, "enable notifications failed");
        }
    }

    private void handleDescriptorWrite(BluetoothGatt g, UUID uuid, int status) {
        Link link = byGatt.get(g);
        if (link == null || link.gatt != g) return;
        if (uuid == null || !uuid.equals(CCCD)) return;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitError("enable notifications failed: " + status, link.mac, null);
            failLink(link, status, "enable notifications failed");
            return;
        }
        if (!announceConnected(link)) return;
        // Say who is on the other end: the watch prints this in the net room.
        // Android rotates the bluetooth address underneath the connection, so
        // a name is what stays meaningful.
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("name", Build.MODEL);
            enqueueMessage(link, hello.toString(), null);
        } catch (Throwable t) { }
    }

    /** Returns true only the first time the link becomes usable. */
    private boolean announceConnected(Link link) {
        if (link.announced) return false;
        if (link.gatt == null || link.writeChar == null || link.notifyChar == null) return false;
        link.announced = true;
        link.state = STATE_CONNECTED;
        link.since = System.currentTimeMillis();
        link.connectSeq = ++connectSeq;
        link.attempt = 0;
        link.nextRetryAt = 0;
        emitConnected(link);
        return true;
    }

    private void handleNotification(BluetoothGatt g, UUID uuid, byte[] value) {
        Link link = byGatt.get(g);
        if (link == null || link.notifyChar == null) return;
        if (uuid == null || !uuid.equals(link.notifyChar.getUuid())) return;
        receiveBytes(link, value);
    }

    private void handleCharacteristicWrite(BluetoothGatt g, int status) {
        Link link = byGatt.get(g);
        if (link == null || link.gatt != g) return;
        WritePart part = link.currentPart;
        handler.removeCallbacks(link.writeTimeout);
        link.writing = false;
        link.currentPart = null;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            link.writeQueue.clear();
            link.writeQueueBytes = 0;
            emitError("write failed: " + status, link.mac, null);
            emitSent(link, part == null ? null : part.id);
            return;
        }
        if (part != null && part.endOfMessage) emitSent(link, part.id);
        pumpWrites(link);
    }

    /** A part that was handed to the stack but never acknowledged. The radio is
     *  gone (asleep, or the link is quietly dead), so waiting for Android's own
     *  supervision timeout only holds everything queued behind it. Fail the
     *  link now: emitError wakes whoever is waiting on this send, and the
     *  disconnect + backoff reconnect is the same road a real drop takes. */
    private void writeTimedOut(Link link) {
        if (!link.writing) return;
        Logger.logInfo(LOG_TAG, "write to " + link.mac + " unacknowledged after "
                + WRITE_ACK_TIMEOUT_MS + "ms; recycling the link");
        WritePart part = link.currentPart;
        emitError("write timeout", link.mac, null);
        if (part != null) Logger.logInfo(LOG_TAG, "write timeout on send id " + part.id
                + " (" + part.bytes.length + " bytes)");
        failLink(link, 0, "write timeout");
    }

    // ---------- receive reassembly ----------

    private void resetRx(Link link) {
        link.rxParts = null;
        link.rxTotal = 0;
        link.rxAt = 0;
    }

    /** Parse "<index>/<total>:" at the start of a notification. Returns
     *  {headerEnd, index, total}, or null when this is a whole message. */
    private static int[] parseFrame(byte[] b) {
        if (b == null || b.length == 0) return null;
        int i = 0;
        int first = 0;
        while (i < b.length && b[i] >= '0' && b[i] <= '9') {
            if (++first > 9) return null;
            i++;
        }
        if (first == 0 || i >= b.length || b[i] != '/') return null;
        i++;
        int second = 0;
        while (i < b.length && b[i] >= '0' && b[i] <= '9') {
            if (++second > 9) return null;
            i++;
        }
        if (second == 0 || i >= b.length || b[i] != ':') return null;
        int index = 0;
        for (int k = 0; k < first; k++) index = index * 10 + (b[k] - '0');
        int total = 0;
        for (int k = 0; k < second; k++) total = total * 10 + (b[first + 1 + k] - '0');
        return new int[] { i + 1, index, total };
    }

    private void receiveBytes(Link link, byte[] value) {
        long now = System.currentTimeMillis();
        // A long pause means a new message, not a lost part. A half-received
        // clip cannot span a gap.
        if (link.rxParts != null && now - link.rxAt > RX_GAP_MS) {
            logMissingParts(link);
            resetRx(link);
        }
        int[] frame = parseFrame(value);
        if (frame == null) {
            // A whole message in one notification (a tap, a state request, the
            // step push): pass it straight through.
            resetRx(link);
            emitMessage(link, new String(value, StandardCharsets.UTF_8));
            return;
        }
        int headerEnd = frame[0];
        int index = frame[1];
        int total = frame[2];
        if (total <= 0 || total > RX_MAX_PARTS || index < 0 || index >= total) {
            Logger.logInfo(LOG_TAG, "bad frame header from " + link.mac + "; dropped");
            resetRx(link);
            return;
        }
        // Fill by index, not by order: parts can arrive out of order or twice,
        // and a single lost part must not wipe the parts already in hand. Only
        // a fresh message (index 0), a different total, or a long pause
        // restarts the buffer.
        if (index == 0 || link.rxParts == null || link.rxTotal != total) {
            if (link.rxParts != null) logMissingParts(link);
            link.rxParts = new ArrayList<byte[]>(total);
            for (int i = 0; i < total; i++) link.rxParts.add(null);
            link.rxTotal = total;
        }
        link.rxParts.set(index, Arrays.copyOfRange(value, headerEnd, value.length));
        link.rxAt = now;
        for (byte[] part : link.rxParts) if (part == null) return;
        // Every part is present: join the bytes and decode UTF-8 exactly once
        // (a multi-byte character may straddle two notifications).
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] part : link.rxParts) joined.write(part, 0, part.length);
        byte[] whole = joined.toByteArray();
        resetRx(link);
        emitMessage(link, new String(whole, StandardCharsets.UTF_8));
    }

    private void logMissingParts(Link link) {
        if (link.rxParts == null) return;
        int missing = 0;
        for (byte[] part : link.rxParts) if (part == null) missing++;
        if (missing > 0) {
            Logger.logInfo(LOG_TAG, missing + " part(s) of a framed message from "
                    + link.mac + " never arrived");
        }
    }

    // ---------- send ----------

    private void sendText(String message, String macRaw, Integer id) {
        String requestedMac = normalizeMac(macRaw);
        Link link;
        if (requestedMac == null || requestedMac.isEmpty()) {
            link = primaryLink(); // no mac => the most recently connected link
        } else {
            link = links.get(requestedMac);
        }
        if (link == null || !isConnected(link)) {
            emitError("not connected", link == null ? requestedMac : link.mac, null);
            return;
        }
        enqueueMessage(link, message == null ? "" : message, id);
    }

    /** Frame one message onto that link's own queue. The framing prefix is
     *  "<index>/<total>:". One part must fit in mtu-3 (the ATT payload limit),
     *  including the prefix. */
    private void enqueueMessage(Link link, String message, Integer id) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        int mtu = Math.max(23, link.mtu);
        int maxPart = Math.max(20, mtu - 3);
        // 12 covers the longest "<i>/<n>:" prefix; this is the old bug: at
        // mtu 23 it produced 20-byte bodies plus a prefix, over the ATT limit.
        int body = Math.max(1, maxPart - FRAME_HEADER_ROOM);
        int total = data.length == 0 ? 1 : (data.length + body - 1) / body;
        // If the message needs so many parts that the prefix outgrows the
        // 12-byte budget, shrink the body until the longest actual prefix plus
        // the body still fits the ATT payload. (Real payloads never get here.)
        while (body > 1) {
            int worstHeader = digits(total - 1) + digits(total) + 2;
            if (worstHeader + body <= maxPart) break;
            body--;
            total = data.length == 0 ? 1 : (data.length + body - 1) / body;
        }
        ArrayList<WritePart> parts = new ArrayList<WritePart>(total);
        int bytes = 0;
        for (int i = 0; i < total; i++) {
            int off = i * body;
            int len = Math.min(body, Math.max(0, data.length - off));
            byte[] head = (i + "/" + total + ":").getBytes(StandardCharsets.US_ASCII);
            byte[] part = new byte[head.length + len];
            System.arraycopy(head, 0, part, 0, head.length);
            if (len > 0) System.arraycopy(data, off, part, head.length, len);
            parts.add(new WritePart(part, i == total - 1, id));
            bytes += part.length;
        }
        // A radio that is asleep takes nothing, and sends are fire-and-forget:
        // without a cap the queue grew for as long as the watch was away. Drop
        // the stuck messages instead of growing (they cannot be delivered
        // whole once their order is broken anyway) and say so, so a caller
        // waiting on this link does not sit out its own deadline.
        if (link.writeQueueBytes + bytes > WRITE_QUEUE_MAX_BYTES) {
            int dropped = link.writeQueueBytes;
            link.writeQueue.clear();
            link.writeQueueBytes = 0;
            Logger.logInfo(LOG_TAG, "write queue for " + link.mac + " overflowed: dropped "
                    + dropped + " queued bytes (the radio is not taking writes)");
            emitError("write queue overflow", link.mac, null);
        }
        for (WritePart p : parts) link.writeQueue.add(p);
        link.writeQueueBytes += bytes;
        pumpWrites(link);
    }

    private static int digits(int n) {
        int d = 1;
        while (n >= 10) { n /= 10; d++; }
        return d;
    }

    private void pumpWrites(Link link) {
        if (link.writing) return;
        if (link.gatt == null || link.writeChar == null) {
            // Nothing can drain this queue: drop it here rather than replay
            // stale messages into whatever link comes next.
            if (!link.writeQueue.isEmpty()) {
                int dropped = link.writeQueueBytes;
                link.writeQueue.clear();
                link.writeQueueBytes = 0;
                Logger.logInfo(LOG_TAG, "no link to " + link.mac + "; dropped "
                        + dropped + " queued bytes");
                emitError("not connected", link.mac, null);
            }
            return;
        }
        WritePart next = link.writeQueue.poll();
        if (next == null) return;
        link.writeQueueBytes = Math.max(0, link.writeQueueBytes - next.bytes.length);
        link.writing = true;
        link.currentPart = next;
        try {
            BluetoothGattCharacteristic c = link.writeChar;
            int props = c.getProperties();
            // Prefer Write Without Response when the peripheral offers it (the
            // watch accepts nothing else); otherwise request a normal write.
            int type = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            c.setWriteType(type);
            c.setValue(next.bytes);
            if (!link.gatt.writeCharacteristic(c)) {
                link.writing = false;
                link.currentPart = null;
                link.writeQueue.clear();
                link.writeQueueBytes = 0;
                emitError("writeCharacteristic returned false", link.mac, null);
                emitSent(link, next.id);
                return;
            }
            // The stack accepted the part; it is not delivered until the write
            // callback says so, and a radio that has slept never sends one.
            handler.removeCallbacks(link.writeTimeout);
            handler.postDelayed(link.writeTimeout, WRITE_ACK_TIMEOUT_MS);
        } catch (Throwable t) {
            link.writing = false;
            link.currentPart = null;
            link.writeQueue.clear();
            link.writeQueueBytes = 0;
            emitError("write: " + t, link.mac, null);
            emitSent(link, next.id);
        }
    }

    // ---------- events ----------

    private void emitConnected(Link link) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "connected");
            o.put("mac", link.mac);
            o.put("mtu", link.mtu);
            o.put("service", link.service.toString());
            o.put("rx", link.rx.toString());
            o.put("tx", link.tx.toString());
            emit(o);
        } catch (Throwable t) { }
    }

    private void emitDisconnected(Link link, int status, String reason, long nextRetryMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "disconnected");
            o.put("mac", link.mac);
            o.put("status", status);
            o.put("reason", reason);
            o.put("next_retry_ms", nextRetryMs);
            emit(o);
        } catch (Throwable t) { }
    }

    private void emitMessage(Link link, String data) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "message");
            o.put("mac", link.mac);
            o.put("data", data);
            emit(o);
        } catch (Throwable t) { }
    }

    private void emitSent(Link link, Integer id) {
        try {
            JSONObject o = new JSONObject();
            o.put("event", "sent");
            o.put("mac", link.mac);
            if (id != null) o.put("id", id.intValue());
            emit(o);
        } catch (Throwable t) { }
    }
}
