package com.liskovsoft.smartyoutubetv2.common.subtitle.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.subtitle.pairing.CaptionPairingServer;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionEdit;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.CaptionConnectionStore;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatCompletionAdapter;
import com.liskovsoft.smartyoutubetv2.common.subtitle.translation.ChatTranslationClient;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One native, explicitly opened pairing window. No credentials or pairing URLs are logged or saved. */
public final class CaptionPairingDialog implements Closeable {
    public interface Active { boolean current(); }
    public interface Read { void complete(CaptionConnectionStore.Snapshot snapshot); }
    public interface Saved { void complete(SaveResult result); }
    /** Methods and their callbacks run on the UI thread; implementations marshal storage to their worker. */
    public interface Host {
        void read(Read completion);
        void save(CaptionConnectionEdit edit, Session session, Saved completion);
        ChatTranslationClient.Job probe(CaptionConnectionEdit edit, CaptionConnectionStore.Snapshot snapshot);
    }
    public static final class SaveResult {
        public final boolean saved;
        public final boolean partial;
        public final boolean cancelled;
        public final String code;
        public final CaptionConnectionStore.Snapshot snapshot;
        public SaveResult(boolean saved, boolean partial, boolean cancelled, String code, CaptionConnectionStore.Snapshot snapshot) {
            this.saved = saved; this.partial = partial; this.cancelled = cancelled; this.code = code; this.snapshot = snapshot;
        }
    }

    /** Thread-safe transport/write gate. Never calls a UI lifecycle method from a network thread. */
    public static final class Session implements CaptionPairingServer.Gate, Closeable {
        private final Active mOwner;
        private volatile boolean mActive = true;
        private final Set<CaptionConnectionEdit> mEdits = Collections.newSetFromMap(new IdentityHashMap<CaptionConnectionEdit, Boolean>());
        private final Set<ChatTranslationClient.Job> mJobs = Collections.newSetFromMap(new IdentityHashMap<ChatTranslationClient.Job, Boolean>());
        private final Set<byte[]> mBodies = Collections.newSetFromMap(new IdentityHashMap<byte[], Boolean>());
        public Session(Active owner) { mOwner = owner; }
        @Override public boolean current() { return mActive && mOwner.current(); }
        /** Linearization point: after this succeeds an old-scope commit may finish, even if close follows. */
        public synchronized boolean beginCommit() { return current(); }
        synchronized boolean hold(byte[] body) {
            if (!current()) { Arrays.fill(body, (byte) 0); return false; }
            mBodies.add(body); return true;
        }
        synchronized void release(byte[] body) { mBodies.remove(body); Arrays.fill(body, (byte) 0); }
        synchronized boolean hold(CaptionConnectionEdit edit) {
            if (!current()) { edit.discard(); return false; }
            mEdits.add(edit); return true;
        }
        synchronized void release(CaptionConnectionEdit edit) { mEdits.remove(edit); edit.discard(); }
        synchronized boolean hold(ChatTranslationClient.Job job) {
            if (!current()) { job.cancel(); return false; }
            mJobs.add(job); return true;
        }
        synchronized void release(ChatTranslationClient.Job job) { mJobs.remove(job); }
        @Override public synchronized void close() {
            mActive = false; // Invalidate before touching any socket, input, credential or queued callback.
            for (CaptionConnectionEdit edit : mEdits) edit.discard();
            for (ChatTranslationClient.Job job : mJobs) job.cancel();
            for (byte[] body : mBodies) Arrays.fill(body, (byte) 0);
            mEdits.clear(); mJobs.clear(); mBodies.clear();
        }
    }

    /** Android-free transport semantics except for main-thread dispatch; injectable without opening a socket. */
    static final class Backend implements CaptionPairingServer.Backend {
        private final Handler mUi;
        private final Session mSession;
        private final Host mHost;
        private final Executor mWorker;
        Backend(Handler ui, Session session, Host host, Executor worker) {
            mUi = ui; mSession = session; mHost = host; mWorker = worker;
        }
        @Override public void read(CaptionPairingServer.Completion completion) {
            mUi.post(() -> {
                if (!mSession.current()) { completion.complete(error(409, "EXPIRED", null)); return; }
                try {
                    mHost.read(snapshot -> {
                        if (!mSession.current()) completion.complete(error(409, "EXPIRED", null));
                        else if (snapshot == null) completion.complete(error(503, "READ_FAILED", null));
                        else completion.complete(new CaptionPairingServer.Reply(200, CaptionConnectionEdit.state(snapshot), false));
                    });
                } catch (RuntimeException failure) { completion.complete(error(503, "READ_FAILED", null)); }
            });
        }
        @Override public void save(byte[] body, CaptionPairingServer.Completion completion) { dispatch(body, false, completion); }
        @Override public void test(byte[] body, CaptionPairingServer.Completion completion) { dispatch(body, true, completion); }
        private void dispatch(byte[] body, boolean test, CaptionPairingServer.Completion completion) {
            if (!mSession.hold(body)) { completion.complete(error(409, "EXPIRED", null)); return; }
            mUi.post(() -> {
                if (!mSession.current()) { mSession.release(body); completion.complete(error(409, "EXPIRED", null)); return; }
                try {
                    mHost.read(snapshot -> accept(body, test, snapshot, completion));
                } catch (RuntimeException failure) {
                    mSession.release(body); completion.complete(error(503, "READ_FAILED", null));
                }
            });
        }
        private void accept(byte[] body, boolean test, CaptionConnectionStore.Snapshot snapshot, CaptionPairingServer.Completion completion) {
            CaptionConnectionEdit edit = null;
            try {
                if (!mSession.current()) { completion.complete(error(409, "EXPIRED", null)); return; }
                if (snapshot == null) { completion.complete(error(503, "READ_FAILED", null)); return; }
                edit = CaptionConnectionEdit.parse(body, snapshot.connection);
                if (test != (edit.operation == CaptionConnectionEdit.Operation.TEST)) {
                    completion.complete(error(400, "INVALID_OPERATION", snapshot)); return;
                }
                if (edit.revision != snapshot.revision) {
                    completion.complete(error(409, "REVISION_CONFLICT", snapshot)); return;
                }
                if (!mSession.hold(edit)) { completion.complete(error(409, "EXPIRED", null)); return; }
                CaptionConnectionEdit pending = edit;
                if (test) probe(pending, snapshot, completion);
                else {
                    mHost.save(pending, mSession, result -> {
                        mSession.release(pending);
                        if (!mSession.current()) { completion.complete(error(409, "EXPIRED", null)); return; }
                        completion.complete(saveReply(result));
                    });
                }
                edit = null; // Ownership transferred to the bounded pending set until acknowledgement.
            } catch (IllegalArgumentException invalid) {
                completion.complete(error(400, "INVALID_INPUT", snapshot));
            } catch (RuntimeException failure) {
                completion.complete(error(503, "SAVE_FAILED", snapshot));
            } finally {
                mSession.release(body);
                if (edit != null) mSession.release(edit);
            }
        }
        private void probe(CaptionConnectionEdit edit, CaptionConnectionStore.Snapshot snapshot, CaptionPairingServer.Completion completion) {
            ChatTranslationClient.Job job;
            try { job = mHost.probe(edit, snapshot); }
            catch (RuntimeException invalid) { mSession.release(edit); completion.complete(probeReply(null)); return; }
            if (job == null || !mSession.hold(job)) {
                mSession.release(edit); completion.complete(probeReply(null)); return;
            }
            try {
                mWorker.execute(() -> {
                    ChatTranslationClient.Result result = null;
                    try { if (mSession.current()) result = job.execute(); }
                    catch (RuntimeException ignored) { /* Provider details and request content are never returned. */ }
                    finally { mSession.release(job); mSession.release(edit); }
                    if (mSession.current()) completion.complete(probeReply(result));
                    else completion.complete(error(409, "EXPIRED", null));
                });
            } catch (RejectedExecutionException stopped) {
                job.cancel(); mSession.release(job); mSession.release(edit); completion.complete(probeReply(null));
            }
        }
    }

    static CaptionPairingServer.Reply saveReply(SaveResult result) {
        if (result == null) return error(503, "SAVE_FAILED", null);
        try {
            JSONObject body = new JSONObject().put("ok", result.saved).put("saved", result.saved)
                    .put("partial", result.partial).put("code", result.code);
            if (result.snapshot != null) body.put("state", new JSONObject(CaptionConnectionEdit.state(result.snapshot)));
            int status = result.saved ? 200 : result.cancelled || "REVISION_CONFLICT".equals(result.code) ? 409 : 503;
            return new CaptionPairingServer.Reply(status, body.toString(), result.saved);
        } catch (JSONException ignored) { return error(503, "SAVE_FAILED", null); }
    }
    static CaptionPairingServer.Reply probeReply(ChatTranslationClient.Result result) {
        boolean truncated = result != null && result.completionState == ChatCompletionAdapter.State.TRUNCATED;
        boolean ok = result != null && result.status == ChatTranslationClient.Status.COMPLETE && !truncated;
        String code = truncated ? "TEST_TRUNCATED" : ok ? "TEST_OK" : "TEST_FAILED";
        try {
            return new CaptionPairingServer.Reply(200, new JSONObject().put("ok", ok).put("code", code)
                    .put("httpCode", result == null ? 0 : result.httpCode).toString(), false);
        } catch (JSONException ignored) { return error(503, "TEST_FAILED", null); }
    }
    static CaptionPairingServer.Reply error(int status, String code, CaptionConnectionStore.Snapshot snapshot) {
        try {
            JSONObject body = new JSONObject().put("ok", false).put("code", code);
            if (snapshot != null) body.put("state", new JSONObject(CaptionConnectionEdit.state(snapshot)));
            return new CaptionPairingServer.Reply(status, body.toString(), false);
        } catch (JSONException ignored) { return new CaptionPairingServer.Reply(503, "{\"ok\":false,\"code\":\"UNAVAILABLE\"}", false); }
    }

    static final class Address {
        final InetAddress address;
        final String label;
        Address(InetAddress address, String label) { this.address = address; this.label = label; }
        @Override public String toString() { return label; }
    }
    private final Context mContext;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private final Session mSession;
    private final ThreadPoolExecutor mWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(2), task -> { Thread thread = new Thread(task, "caption-pairing-ui"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private final Backend mBackend;
    private final Object mLock = new Object();
    private boolean mClosed;
    private CaptionPairingServer mServer;
    private AlertDialog mDialog;
    private ImageView mCode;
    private TextView mStatus;
    private Spinner mAddresses;
    private Bitmap mBitmap;

    private CaptionPairingDialog(Context context, Active owner, Host host) {
        mContext = context; mSession = new Session(owner); mBackend = new Backend(mUi, mSession, host, mWorker);
    }
    public static CaptionPairingDialog show(Context context, Active owner, Host host) {
        CaptionPairingDialog pairing = new CaptionPairingDialog(context, owner, host);
        pairing.show(); return pairing;
    }
    private void show() {
        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * mContext.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView notice = new TextView(mContext); notice.setText(R.string.caption_pairing_notice); content.addView(notice);
        mStatus = new TextView(mContext); mStatus.setText(R.string.caption_ai_loading); content.addView(mStatus);
        mAddresses = new Spinner(mContext); content.addView(mAddresses);
        mCode = new ImageView(mContext); mCode.setAdjustViewBounds(true);
        mCode.setContentDescription(mContext.getString(R.string.caption_pairing_code));
        content.addView(mCode, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (240 * mContext.getResources().getDisplayMetrics().density)));
        ScrollView scroll = new ScrollView(mContext); scroll.addView(content);
        mDialog = new AlertDialog.Builder(mContext, R.style.AppDialog).setTitle(R.string.caption_pairing_title)
                .setView(scroll).setPositiveButton(R.string.caption_pairing_start, null).setNegativeButton(android.R.string.cancel, null).create();
        mDialog.setOnDismissListener(ignored -> close());
        mDialog.setOnShowListener(ignored -> {
            mDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        });
        mDialog.show();
        mWorker.execute(() -> {
            List<Address> addresses;
            try { addresses = addresses(mContext.getApplicationContext()); }
            catch (RuntimeException | IOException unavailable) { addresses = Collections.emptyList(); }
            List<Address> found = addresses;
            mUi.post(() -> {
                if (!mSession.current()) return;
                if (found.isEmpty()) { mStatus.setText(R.string.caption_pairing_no_network); return; }
                mAddresses.setAdapter(new ArrayAdapter<>(mContext, android.R.layout.simple_spinner_dropdown_item, found));
                mStatus.setText(R.string.caption_pairing_choose_network);
                mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (!mSession.current()) return;
                    bind(found.get(mAddresses.getSelectedItemPosition()));
                });
                if (found.size() == 1) bind(found.get(0));
            });
        });
    }
    private void bind(Address address) {
        mAddresses.setEnabled(false);
        mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        mStatus.setText(mContext.getString(R.string.caption_pairing_address, address.label));
        mWorker.execute(() -> {
            CaptionPairingServer server = null;
            Bitmap bitmap = null;
            try {
                Map<String, CaptionPairingServer.Asset> assets = assets(mContext);
                if (!mSession.current()) return;
                server = CaptionPairingServer.start(address.address, assets, SystemClock::elapsedRealtime, mSession, mBackend,
                        saved -> { close(); }); // Core invokes this after flushing a successful response, never from the save callback.
                synchronized (mLock) {
                    if (mClosed) { server.close(); return; }
                    mServer = server;
                }
                bitmap = qr(server.pairingUrl());
                Bitmap ready = bitmap;
                mUi.post(() -> {
                    if (!mSession.current()) { ready.recycle(); return; }
                    mBitmap = ready; mCode.setImageBitmap(ready);
                });
            } catch (IOException | WriterException | RuntimeException unavailable) {
                if (bitmap != null) bitmap.recycle();
                if (server != null) server.close();
                mUi.post(() -> { if (mSession.current()) mStatus.setText(R.string.caption_pairing_unavailable); });
            }
        });
    }
    @Override public void close() {
        mSession.close();
        CaptionPairingServer server;
        synchronized (mLock) {
            if (mClosed) return;
            mClosed = true; server = mServer; mServer = null;
        }
        if (server != null) server.close();
        mWorker.shutdownNow();
        mUi.post(() -> {
            if (mDialog != null) mDialog.dismiss();
            if (mCode != null) mCode.setImageDrawable(null);
            if (mBitmap != null) { mBitmap.recycle(); mBitmap = null; }
        });
    }

    static Bitmap qr(String value) throws WriterException {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Pairing unavailable");
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 384, 384, hints);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
            pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        return Bitmap.createBitmap(pixels, matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
    }
    private static Map<String, CaptionPairingServer.Asset> assets(Context context) throws IOException {
        Map<String, CaptionPairingServer.Asset> assets = new LinkedHashMap<>();
        String[] names = {"index.html", "pair.js", "pair.css"};
        String[] paths = {"/", "/pair.js", "/pair.css"};
        String[] types = {"text/html; charset=utf-8", "application/javascript; charset=utf-8", "text/css; charset=utf-8"};
        for (int i = 0; i < names.length; i++) {
            try (InputStream input = context.getAssets().open("caption-pairing/" + names[i]); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 60000) throw new IOException("Pairing asset unavailable");
                    output.write(buffer, 0, count);
                }
                assets.put(paths[i], new CaptionPairingServer.Asset(types[i], output.toByteArray()));
            }
        }
        return assets;
    }
    static boolean privateAddress(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) return (bytes[0] & 254) == 252;
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
    }
    /** Pure legacy interface identity check. MAC data is transient and never included in labels or state. */
    static boolean matchesWifiAddress(boolean connected, int ipv4, String wifiMac, byte[] hardware, InetAddress address) {
        if (!connected || address == null) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4 && ipv4 != 0 &&
                (bytes[0] & 255) == (ipv4 & 255) && (bytes[1] & 255) == ((ipv4 >>> 8) & 255) &&
                (bytes[2] & 255) == ((ipv4 >>> 16) & 255) && (bytes[3] & 255) == ((ipv4 >>> 24) & 255)) return true;
        if (wifiMac == null || wifiMac.length() != 17 || hardware == null || hardware.length != 6) return false;
        for (int i = 0; i < hardware.length; i++) {
            int offset = i * 3;
            if (i > 0 && wifiMac.charAt(offset - 1) != ':') return false;
            int high = Character.digit(wifiMac.charAt(offset), 16), low = Character.digit(wifiMac.charAt(offset + 1), 16);
            if (high < 0 || low < 0 || (hardware[i] & 255) != (high << 4 | low)) return false;
        }
        return true;
    }
    private static List<Address> addresses(Context context) throws IOException {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return Collections.emptyList();
        if (Build.VERSION.SDK_INT >= 21) return Api21.addresses(manager);
        // Before public LinkProperties APIs, only connected, recognisable physical interfaces qualify.
        NetworkInfo wifi = manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        NetworkInfo ethernet = manager.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
        boolean wired = ethernet != null && ethernet.isConnected();
        boolean wifiConnected = wifi != null && wifi.isConnected();
        int wifiAddress = 0;
        String wifiMac = null;
        if (wifiConnected) {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info != null) { wifiAddress = info.getIpAddress(); wifiMac = info.getMacAddress(); }
        }
        List<Address> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return result;
        while (interfaces.hasMoreElements()) {
            NetworkInterface device = interfaces.nextElement();
            if (!device.isUp() || device.isLoopback() || device.isVirtual()) continue;
            String name = device.getName();
            if (name.matches("(?i)(tun|tap|ppp|wg|ipsec|rmnet|ccmni|pdp).*")) continue;
            boolean ethernetInterface = wired && name.matches("(eth|en)[0-9]+[a-zA-Z0-9_.-]*");
            byte[] hardware = null;
            if (wifiConnected && wifiMac != null) {
                try { hardware = device.getHardwareAddress(); }
                catch (java.net.SocketException unavailable) { /* Keep the existing IPv4 and Ethernet paths usable. */ }
            }
            Enumeration<InetAddress> list = device.getInetAddresses();
            while (list.hasMoreElements()) {
                InetAddress address = list.nextElement();
                boolean wifiInterface = matchesWifiAddress(wifiConnected, wifiAddress, wifiMac, hardware, address);
                if (privateAddress(address) && (wifiInterface || ethernetInterface))
                    result.add(new Address(address, name + " · " + address.getHostAddress()));
            }
        }
        return result;
    }
    @android.annotation.TargetApi(21)
    private static final class Api21 {
        static List<Address> addresses(ConnectivityManager manager) {
            Map<String, Address> result = new LinkedHashMap<>();
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties links = manager.getLinkProperties(network);
                if (links == null) continue;
                for (LinkAddress link : links.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (privateAddress(address)) result.put(address.getHostAddress(), new Address(address, links.getInterfaceName() + " · " + address.getHostAddress()));
                }
            }
            return new ArrayList<>(result.values());
        }
    }
}
