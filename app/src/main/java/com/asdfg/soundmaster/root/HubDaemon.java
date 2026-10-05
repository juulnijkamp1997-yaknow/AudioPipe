package com.asdfg.soundmaster.root;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.os.HandlerThread;
import android.os.Looper;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Bluetooth hub: the chosen apps play on the Bluetooth speaker, everything else stays on the
 * phone. Runs as root in its own long-lived process, started by the app:
 *
 *   CLASSPATH=APK app_process /system/bin com.asdfg.soundmaster.root.RoutingHelper hub FILES_DIR VERSION
 *
 * FILES_DIR is the app's files directory. The app writes hub.conf there (the uids of the chosen
 * apps) and creates hub.stop to stop the hub. This process writes hub.status there (state,
 * speaker, heartbeat) as a file owned by the app, so the app reads it without root.
 *
 * How it routes:
 * - One dynamic audio policy with a RENDER mix: playback from the chosen uids goes to the
 *   connected Bluetooth A2DP device. Android's audio policy manager applies that rule itself;
 *   nothing is captured or replayed. The policy belongs to this process: when the process dies,
 *   Android removes it and routing is back to normal.
 * - Every other kind of sound (media, games, assistant, TalkBack, notifications, ringtones,
 *   alarms) prefers the phone: its speaker, or wired or USB headphones when plugged in. Calls
 *   are left to Android.
 * - Multi audio focus, so the app on the speaker and an app on the phone do not pause each other.
 * - Compressed offload playback is off while the hub runs: offloaded tracks never match a mix
 *   and would stay on the phone.
 *
 * Needs "Disable Bluetooth A2DP hardware offload". With hardware offload, Bluetooth is played
 * through the phone's main output, and a mix on that output pulls the phone's own sounds to the
 * speaker as well. Checked before starting and after every registration.
 */
final class HubDaemon {

    private static final String CONFIG = "hub.conf";
    private static final String STATUS = "hub.status";
    private static final String STOP = "hub.stop";

    private static final long POLL_MS = 500;
    private static final long HEARTBEAT_MS = 5_000;
    private static final long VERIFY_MS = 30_000;
    private static final long RETRY_MS = 15_000;
    private static final long PHONE_CHECK_MS = 10_000;

    private static final int RULE_MATCH_UID = 0x1 << 2;     // AudioMixingRule.RULE_MATCH_UID
    private static final int ROUTE_FLAG_RENDER = 0x1;       // AudioMix.ROUTE_FLAG_RENDER
    private static final int AUDIO_MANAGER_SUCCESS = 0;     // AudioManager.SUCCESS

    private static final String A2DP_OFFLOAD_SUPPORTED = "ro.bluetooth.a2dp_offload.supported";
    private static final String A2DP_OFFLOAD_DISABLED = "persist.bluetooth.a2dp_offload.disabled";
    private static final String PLAYBACK_OFFLOAD_DISABLE = "audio.offload.disable";

    /** Sound types that stay on the phone while the hub runs. */
    static final int[] PHONE_USAGES = {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, // TalkBack
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ALARM,
    };

    /** A strategy that also carries calls is left alone: Android routes calls itself. */
    static final int[] CALL_USAGES = {
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
    };

    /** "The phone" is the first of these that is connected. */
    static final int[] PHONE_DEVICE_TYPES = {
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    };

    private final Object audio;
    private final Looper looper;
    private final File dir;
    private final File configFile;
    private final File statusFile;
    private final File stopFile;
    private final String version;
    private final int pid = Os.getpid();

    private int ownerUid = -1;
    private String ownerContext;

    private String configText;
    private int[] uids = new int[0];

    private Map<Integer, String> phoneStrategies = Collections.emptyMap();
    private String phoneDeviceKey;
    private long lastPhoneCheck;

    private Object policy;                  // the registered AudioPolicy, or null
    private int policyDeviceId = -1;
    private int[] policyUids = new int[0];
    private long lastVerify;

    private String failedKey;
    private long failedAt;
    private String blockedReason;           // set when retrying cannot help until a reboot

    private String state = "starting";
    private String code = "";
    private String detail = "";
    private String deviceName = "";
    private String lastStatus = "";
    private long lastStatusWrite;

    private String playbackOffloadBefore;
    private boolean playbackOffloadChanged;
    private boolean multiFocusChanged;

    private HubDaemon(Object audio, Looper looper, File dir, String version) {
        this.audio = audio;
        this.looper = looper;
        this.dir = dir;
        this.version = version;
        configFile = new File(dir, CONFIG);
        statusFile = new File(dir, STATUS);
        stopFile = new File(dir, STOP);
        try {
            ownerUid = Os.stat(dir.getPath()).st_uid;
        } catch (Exception e) {
            log("cannot read the owner of " + dir + ": " + e);
        }
        ownerContext = selinuxContext(dir.getPath());
    }

    /** The "hub" command: runs until the app creates hub.stop. */
    static void run(String filesDir, String version) throws Exception {
        HandlerThread policyThread = new HandlerThread("audiopipe-hub-policy");
        policyThread.start();
        new HubDaemon(RoutingHelper.audioService(), policyThread.getLooper(), new File(filesDir), version).loop();
    }

    /**
     * The "hub-release" command: undoes what a hub that did not stop cleanly left behind.
     * Its audio policy is already gone with its process; this resets the rest.
     */
    static void releaseAll() throws Exception {
        Object audio = RoutingHelper.audioService();
        releasePhoneStrategies(audio);
        RoutingHelper.setMultiAudioFocus(audio, false);
        if ("true".equals(getProp(PLAYBACK_OFFLOAD_DISABLE))) {
            setProp(PLAYBACK_OFFLOAD_DISABLE, "false");
            System.out.println("compressed offload playback: back on");
        }
    }

    // ---- main loop ----

    private void loop() {
        log("hub " + version + " started, pid " + pid + ", files in " + dir);
        if (otherHubRunning()) {
            log("another hub is already running, exiting");
            return;
        }
        stopFile.delete();
        writeStatus(true);
        try {
            boolean ready = false;
            try {
                ready = setUp();
            } catch (Throwable t) {
                fail("setup", RoutingHelper.unwrap(t));
            }
            while (!stopFile.exists()) {
                if (ready) {
                    try {
                        tick();
                    } catch (Throwable t) {
                        fail("internal", RoutingHelper.unwrap(t));
                    }
                }
                writeStatus(false);
                sleep(POLL_MS);
            }
            log("stop requested");
        } finally {
            tearDown();
        }
    }

    /** Returns false when the hub cannot work on this phone as it is set up now. */
    private boolean setUp() throws Exception {
        if ("true".equals(getProp(A2DP_OFFLOAD_SUPPORTED)) && !"true".equals(getProp(A2DP_OFFLOAD_DISABLED))) {
            setState("error", "offload", "Bluetooth A2DP hardware offload is on");
            log("Bluetooth A2DP hardware offload is on: not routing anything");
            return false;
        }

        phoneStrategies = RoutingHelper.selectStrategies(audio, PHONE_USAGES, CALL_USAGES);
        log("sound types kept on the phone: " + phoneStrategies);
        if (phoneStrategies.isEmpty()) {
            setState("error", "strategies", "no routing strategy found for media or notifications");
            return false;
        }

        RoutingHelper.setMultiAudioFocus(audio, true);
        multiFocusChanged = true;

        playbackOffloadBefore = getProp(PLAYBACK_OFFLOAD_DISABLE);
        if (!"true".equals(playbackOffloadBefore)) {
            setProp(PLAYBACK_OFFLOAD_DISABLE, "true");
            playbackOffloadChanged = true;
        }
        return true;
    }

    private void tick() throws Exception {
        reloadConfig();
        AudioDeviceInfo[] devices = RoutingHelper.outputDevices();
        AudioDeviceInfo bluetooth = find(devices, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP);
        deviceName = bluetooth == null ? "" : String.valueOf(bluetooth.getProductName());

        routeChosenApps(bluetooth);
        // After the route: when the hub starts while music plays on the speaker, the chosen
        // app moves straight to its route instead of passing through the phone first
        keepPhoneSoundsOnPhone(devices);
    }

    private void routeChosenApps(AudioDeviceInfo bluetooth) throws Exception {
        if (blockedReason != null) {
            unregister(blockedReason);
            return;
        }
        if (uids.length == 0) {
            unregister("no apps chosen");
            setState("idle", "no_apps", "");
            return;
        }
        if (bluetooth == null) {
            unregister("no Bluetooth speaker connected");
            setState("waiting", "", "");
            return;
        }

        boolean upToDate = policy != null && policyDeviceId == bluetooth.getId() && Arrays.equals(policyUids, uids);
        if (!upToDate) {
            // The device id changes whenever the speaker reconnects or Android reopens its output,
            // and the mix has to be registered again then: Android does not move it to the new output.
            String key = bluetooth.getId() + " " + Arrays.toString(uids);
            if (key.equals(failedKey) && System.currentTimeMillis() - failedAt < RETRY_MS) {
                return;
            }
            unregister("route changed");
            register(bluetooth, key);
            return;
        }
        if (System.currentTimeMillis() - lastVerify > VERIFY_MS) {
            verify(bluetooth, policyDeviceId + " " + Arrays.toString(policyUids));
        }
    }

    private void tearDown() {
        unregister("hub stopped");
        try {
            releasePhoneStrategies(audio);
        } catch (Throwable t) {
            log("could not reset the phone sounds: " + RoutingHelper.unwrap(t));
        }
        if (multiFocusChanged) {
            try {
                RoutingHelper.setMultiAudioFocus(audio, false);
            } catch (Throwable t) {
                log("could not turn multi audio focus off: " + RoutingHelper.unwrap(t));
            }
        }
        if (playbackOffloadChanged) {
            setProp(PLAYBACK_OFFLOAD_DISABLE, playbackOffloadBefore == null || playbackOffloadBefore.isEmpty()
                    ? "false" : playbackOffloadBefore);
        }
        setState("stopped", "", "");
        writeStatus(true);
        stopFile.delete();
        log("hub stopped");
    }

    // ---- the route to the speaker ----

    private void register(AudioDeviceInfo bluetooth, String key) throws Exception {
        Object newPolicy = buildPolicy(uids, bluetooth);
        String problem = registerPolicy(newPolicy);
        if (problem != null) {
            failedKey = key;
            failedAt = System.currentTimeMillis();
            setState("error", "register", problem);
            log("could not route to " + bluetooth.getProductName() + ": " + problem);
            return;
        }
        policy = newPolicy;
        policyDeviceId = bluetooth.getId();
        policyUids = uids.clone();
        log("routing uids " + Arrays.toString(uids) + " to " + bluetooth.getProductName()
                + " (device id " + bluetooth.getId() + ")");
        verify(bluetooth, key);
    }

    /**
     * Makes sure the mix landed on an output of its own. On an output shared with the phone's
     * speaker it would pull the phone's own sounds to the Bluetooth speaker too.
     */
    private void verify(AudioDeviceInfo bluetooth, String key) {
        lastVerify = System.currentTimeMillis();
        int[] binding = mixBinding(bluetooth.getAddress());
        int output = binding[0];
        int primary = binding[1];
        if (output < 0) {
            setState("active", "", "route check not possible");
            log("route check not possible: no matching mix in the audio policy dump");
            return;
        }
        if (output == 0) {
            unregister("the route has no output");
            failedKey = key;
            failedAt = System.currentTimeMillis();
            setState("error", "no_output", "the route has no output");
            return;
        }
        if (output == primary) {
            unregister("the speaker shares the phone's main output");
            blockedReason = "Bluetooth shares the phone's main output (" + output + ")";
            setState("error", "shared_output", blockedReason);
            return;
        }
        setState("active", "", "output " + output);
    }

    private Object buildPolicy(int[] chosenUids, AudioDeviceInfo bluetooth) throws Exception {
        Class<?> ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object ruleBuilder = ruleBuilderClass.getConstructor().newInstance();
        Method addMixRule = ruleBuilderClass.getMethod("addMixRule", int.class, Object.class);
        for (int uid : chosenUids) {
            addMixRule.invoke(ruleBuilder, RULE_MATCH_UID, Integer.valueOf(uid));
        }
        Object rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder);

        Class<?> ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule);
        mixBuilderClass.getMethod("setRouteFlags", int.class).invoke(mixBuilder, ROUTE_FLAG_RENDER);
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(48000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        mixBuilderClass.getMethod("setFormat", AudioFormat.class).invoke(mixBuilder, format);
        mixBuilderClass.getMethod("setDevice", AudioDeviceInfo.class).invoke(mixBuilder, bluetooth);
        Object mix = mixBuilderClass.getMethod("build").invoke(mixBuilder);

        // A null context is fine: the policy then uses this process's own attribution
        Class<?> policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object policyBuilder = policyBuilderClass.getConstructor(Context.class).newInstance((Object) null);
        policyBuilderClass.getMethod("addMix", mix.getClass()).invoke(policyBuilder, mix);
        policyBuilderClass.getMethod("setLooper", Looper.class).invoke(policyBuilder, looper);
        return policyBuilderClass.getMethod("build").invoke(policyBuilder);
    }

    /** Returns null on success, otherwise what went wrong. */
    private String registerPolicy(Object newPolicy) throws Exception {
        try {
            Method register = Class.forName("android.media.AudioManager")
                    .getDeclaredMethod("registerAudioPolicyStatic", newPolicy.getClass());
            register.setAccessible(true);
            int result = (Integer) register.invoke(null, newPolicy);
            return result == AUDIO_MANAGER_SUCCESS ? null : "the audio service refused the route (" + result + ")";
        } catch (NoSuchMethodException e) {
            log("AudioManager.registerAudioPolicyStatic not found, calling the audio service directly");
        }

        Method register = null;
        for (Method method : Class.forName("android.media.IAudioService").getMethods()) {
            if (method.getName().equals("registerAudioPolicy")) {
                register = method;
                break;
            }
        }
        if (register == null) {
            return "this Android version has no registerAudioPolicy";
        }
        Class<?>[] types = register.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            String name = types[i].getName();
            if (name.endsWith("AudioPolicyConfig")) {
                args[i] = newPolicy.getClass().getMethod("getConfig").invoke(newPolicy);
            } else if (name.endsWith("IAudioPolicyCallback")) {
                args[i] = newPolicy.getClass().getMethod("cb").invoke(newPolicy);
            } else if (name.endsWith("AttributionSource")) {
                args[i] = newPolicy.getClass().getMethod("getAttributionSource").invoke(newPolicy);
            } else if (types[i] == boolean.class) {
                args[i] = Boolean.FALSE;   // no focus listener, no focus policy, no volume controller
            } else {
                args[i] = null;            // no media projection
            }
        }
        Object registration = register.invoke(audio, args);
        if (registration == null) {
            return "the audio service refused the route";
        }
        newPolicy.getClass().getMethod("setRegistration", String.class).invoke(newPolicy, registration);
        return null;
    }

    private void unregister(String reason) {
        if (policy == null) {
            return;
        }
        try {
            Object callback = policy.getClass().getMethod("cb").invoke(policy);
            Method unregister;
            try {
                unregister = RoutingHelper.method(audio, "unregisterAudioPolicy", 1);
            } catch (NoSuchMethodException e) {
                unregister = RoutingHelper.method(audio, "unregisterAudioPolicyAsync", 1);
            }
            unregister.invoke(audio, callback);
            log("route removed: " + reason);
        } catch (Throwable t) {
            log("could not remove the route: " + RoutingHelper.unwrap(t));
        }
        policy = null;
        policyDeviceId = -1;
        policyUids = new int[0];
    }

    /**
     * Reads from the audio policy dump which output our mix uses. Returns {output, primary output};
     * output is -1 when the dump has no mix for this address.
     */
    private static int[] mixBinding(String address) {
        return parseMixBinding(exec("dumpsys", "media.audio_policy"), address);
    }

    static int[] parseMixBinding(String dump, String address) {
        int output = -1;
        int primary = -1;
        boolean inMix = false;
        boolean ourMix = false;
        int mixOutput = -1;
        for (String raw : dump.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Primary Output I/O handle:")) {
                primary = parseInt(line.substring(line.indexOf(':') + 1).trim(), -1);
            } else if (line.startsWith("Audio Policy Mix ")) {
                if (inMix && ourMix && mixOutput >= 0) {
                    output = mixOutput;
                }
                inMix = true;
                ourMix = false;
                mixOutput = -1;
            } else if (inMix && line.startsWith("- device address:")) {
                ourMix = line.substring(line.indexOf(':') + 1).trim().equalsIgnoreCase(address);
            } else if (inMix && line.startsWith("- output:")) {
                mixOutput = parseInt(line.substring(line.indexOf(':') + 1).trim(), -1);
            }
        }
        if (inMix && ourMix && mixOutput >= 0) {
            output = mixOutput;
        }
        return new int[] {output, primary};
    }

    // ---- everything else stays on the phone ----

    private void keepPhoneSoundsOnPhone(AudioDeviceInfo[] devices) throws Exception {
        AudioDeviceInfo phone = null;
        for (int type : PHONE_DEVICE_TYPES) {
            phone = find(devices, type);
            if (phone != null) {
                break;
            }
        }
        int type = phone == null ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : phone.getType();
        String address = phone == null ? "" : phone.getAddress();
        String key = type + "/" + address;
        long now = System.currentTimeMillis();
        if (key.equals(phoneDeviceKey)) {
            // Only compare now and then. Setting the same preference again is not free: every
            // routing update makes Android restart the chosen app's playback for a moment.
            if (now - lastPhoneCheck < PHONE_CHECK_MS) {
                return;
            }
            lastPhoneCheck = now;
            if (phonePreferenceIntact(type)) {
                return;
            }
            log("something else changed where the phone sounds go, setting it again");
        }
        lastPhoneCheck = now;
        List<Object> preferred = Collections.singletonList(RoutingHelper.deviceAttributes(type, address));
        Method set = RoutingHelper.method(audio, "setPreferredDevicesForStrategy", 2);
        int failures = 0;
        for (Map.Entry<Integer, String> strategy : phoneStrategies.entrySet()) {
            int result = (Integer) set.invoke(audio, strategy.getKey(), preferred);
            if (result != 0) {
                failures++;
                log("strategy " + strategy.getKey() + " " + strategy.getValue() + ": error " + result);
            }
        }
        if (failures == phoneStrategies.size()) {
            throw new IllegalStateException("could not keep any sound type on the phone");
        }
        phoneDeviceKey = key;
        log("phone sounds play on device type " + type);
    }

    private boolean phonePreferenceIntact(int type) throws Exception {
        Method get = RoutingHelper.method(audio, "getPreferredDevicesForStrategy", 1);
        for (Integer strategy : phoneStrategies.keySet()) {
            List<?> current = (List<?>) get.invoke(audio, strategy);
            if (current == null || !RoutingHelper.isSingleDeviceOfType(current, new int[] {type})) {
                return false;
            }
        }
        return true;
    }

    /** Removes the phone preference from every strategy the hub sets, when it is still ours. */
    private static void releasePhoneStrategies(Object audio) throws Exception {
        Map<Integer, String> strategies = RoutingHelper.selectStrategies(audio, PHONE_USAGES, CALL_USAGES);
        Method get = RoutingHelper.method(audio, "getPreferredDevicesForStrategy", 1);
        Method remove = RoutingHelper.method(audio, "removePreferredDevicesForStrategy", 1);
        for (Map.Entry<Integer, String> strategy : strategies.entrySet()) {
            List<?> current = (List<?>) get.invoke(audio, strategy.getKey());
            String label = "strategy " + strategy.getKey() + " " + strategy.getValue();
            if (current == null || current.isEmpty()) {
                continue;
            }
            if (!RoutingHelper.isSingleDeviceOfType(current, PHONE_DEVICE_TYPES)) {
                System.out.println(label + ": left alone, set by something else: " + current);
                continue;
            }
            int result = (Integer) remove.invoke(audio, strategy.getKey());
            System.out.println(label + ": back to normal routing: " + (result == 0 ? "ok" : "error " + result));
        }
    }

    // ---- files shared with the app ----

    private void reloadConfig() {
        String text = readFile(configFile);
        if (text.equals(configText)) {
            return;
        }
        configText = text;
        int[] next = new int[0];
        for (String line : text.split("\n")) {
            if (!line.startsWith("uids=")) {
                continue;
            }
            String[] parts = line.substring(5).trim().split(",");
            int[] parsed = new int[parts.length];
            int count = 0;
            for (String part : parts) {
                int uid = parseInt(part.trim(), -1);
                if (uid > 0) {
                    parsed[count++] = uid;
                }
            }
            next = Arrays.copyOf(parsed, count);
        }
        Arrays.sort(next);
        if (!Arrays.equals(next, uids)) {
            uids = next;
            log("apps for the speaker (uids): " + Arrays.toString(uids));
        }
    }

    private boolean otherHubRunning() {
        Map<String, String> previous = parseStatus(readFile(statusFile));
        int other = parseInt(previous.get("pid"), -1);
        if (other <= 0 || other == pid || "stopped".equals(previous.get("state"))) {
            return false;
        }
        String cmdline = readFile(new File("/proc/" + other + "/cmdline")).replace('\0', ' ');
        return cmdline.contains(RoutingHelper.class.getName()) && cmdline.contains(" hub ");
    }

    private void writeStatus(boolean force) {
        String body = "state=" + state + "\n"
                + "code=" + code + "\n"
                + "detail=" + oneLine(detail) + "\n"
                + "device=" + oneLine(deviceName) + "\n"
                + "uids=" + join(policy != null ? policyUids : uids) + "\n"
                + "version=" + version + "\n"
                + "pid=" + pid + "\n";
        long now = System.currentTimeMillis();
        if (!force && body.equals(lastStatus) && now - lastStatusWrite < HEARTBEAT_MS) {
            return;
        }
        lastStatus = body;
        lastStatusWrite = now;
        File temp = new File(dir, STATUS + ".tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write((body + "time=" + now + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log("cannot write the status: " + e);
            return;
        }
        handOverToApp(temp);
        if (!temp.renameTo(statusFile)) {
            log("cannot replace " + statusFile);
        }
    }

    /** Gives a file written as root the app's owner and SELinux label, so the app can read it. */
    private void handOverToApp(File file) {
        try {
            if (ownerUid >= 0) {
                Os.chown(file.getPath(), ownerUid, ownerUid);
            }
            Os.chmod(file.getPath(), 0600);
        } catch (Exception e) {
            log("cannot hand " + file + " to the app: " + e);
        }
        if (ownerContext != null) {
            try {
                Class.forName("android.os.SELinux")
                        .getMethod("setFileContext", String.class, String.class)
                        .invoke(null, file.getPath(), ownerContext);
            } catch (Throwable t) {
                log("cannot label " + file + ": " + RoutingHelper.unwrap(t));
            }
        }
    }

    // ---- small helpers ----

    private void setState(String newState, String newCode, String newDetail) {
        if (!newState.equals(state) || !newCode.equals(code)) {
            log("state " + newState + (newCode.isEmpty() ? "" : " (" + newCode + ")")
                    + (newDetail.isEmpty() ? "" : ": " + newDetail));
        }
        state = newState;
        code = newCode;
        detail = newDetail;
    }

    private void fail(String failCode, Throwable cause) {
        setState("error", failCode, String.valueOf(cause));
        StringBuilder trace = new StringBuilder();
        for (StackTraceElement element : cause.getStackTrace()) {
            trace.append("\n    at ").append(element);
        }
        log(failCode + " failed: " + cause + trace);
    }

    private static AudioDeviceInfo find(AudioDeviceInfo[] devices, int type) {
        for (AudioDeviceInfo device : devices) {
            if (device.getType() == type) {
                return device;
            }
        }
        return null;
    }

    static Map<String, String> parseStatus(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return values;
    }

    private static String readFile(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = in.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return text.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String exec(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getProp(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, name);
        } catch (Throwable t) {
            return exec("getprop", name).trim();
        }
    }

    private static void setProp(String name, String value) {
        try {
            Class.forName("android.os.SystemProperties")
                    .getMethod("set", String.class, String.class).invoke(null, name, value);
        } catch (Throwable t) {
            exec("setprop", name, value);
        }
    }

    private static String selinuxContext(String path) {
        try {
            return (String) Class.forName("android.os.SELinux")
                    .getMethod("getFileContext", String.class).invoke(null, path);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String join(int[] values) {
        StringBuilder text = new StringBuilder();
        for (int value : values) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(value);
        }
        return text.toString();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String message) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        System.out.println(time + " " + message);
        System.out.flush();
    }
}
