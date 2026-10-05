package com.asdfg.soundmaster.root;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.IBinder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes system audio routing. Runs as root in its own process, not inside the app:
 *
 *   su -c "CLASSPATH=APK_PATH /system/bin/app_process /system/bin com.asdfg.soundmaster.root.RoutingHelper COMMAND"
 *
 * The audio service's routing methods need MODIFY_AUDIO_ROUTING, which a normal app
 * cannot hold but root passes. Plain Java on purpose: no Kotlin runtime, no Android
 * app context, only framework classes and reflection.
 *
 * Commands:
 *   hub FILES_DIR VERSION
 *                  the Bluetooth hub: keeps running, sends the chosen apps to the Bluetooth
 *                  speaker and keeps everything else on the phone (see HubDaemon)
 *   hub-release    undo what a hub that did not stop cleanly left behind
 *   keep-on-phone  (older versions) play TalkBack, notifications, ringtones and alarms on the
 *                  phone speaker, also while a Bluetooth speaker is connected, and let media
 *                  apps play side by side instead of pausing each other
 *   release        undo keep-on-phone
 *   status         print the output devices and how each sound type is routed
 *   devices        print only the output devices this process can see
 *
 * The last line printed is always "RESULT OK" or "RESULT FAILED: reason".
 */
public final class RoutingHelper {

    /** Sounds that stay on the phone while music plays on a Bluetooth speaker. */
    private static final int[] PHONE_USAGES = {
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, // TalkBack
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ALARM,
    };

    /** A strategy that also carries any of these is never touched: music and calls keep their normal routing. */
    private static final int[] PROTECTED_USAGES = {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
            // Not USAGE_CALL_ASSISTANT: it is a system usage, and AudioAttributes.Builder.setUsage
            // rejects it ("Invalid usage 17"), which broke the whole command on Android 17
    };

    /** Shown in the status output. */
    private static final int[] REPORTED_USAGES = {
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
    };

    private static final int ROLE_OUTPUT = 2;              // AudioDeviceAttributes.ROLE_OUTPUT
    private static final int GET_DEVICES_OUTPUTS = 2;      // AudioManager.GET_DEVICES_OUTPUTS
    private static final int AUDIO_SYSTEM_SUCCESS = 0;     // AudioSystem.SUCCESS

    private RoutingHelper() {
    }

    public static void main(String[] args) {
        String command = args.length > 0 ? args[0] : "status";
        try {
            switch (command) {
                case "hub":
                    if (args.length < 2) {
                        throw new IllegalArgumentException("hub needs the app's files directory");
                    }
                    HubDaemon.run(args[1], args.length > 2 ? args[2] : "?");
                    break;
                case "hub-release":
                    HubDaemon.releaseAll();
                    break;
                case "keep-on-phone":
                    keepOnPhone();
                    break;
                case "release":
                    release();
                    break;
                case "status":
                    status();
                    break;
                case "devices":
                    // No audio service calls: also usable when run under the app's own uid
                    printOutputDevices("Output devices seen by this process");
                    break;
                default:
                    throw new IllegalArgumentException("unknown command: " + command);
            }
            System.out.println("RESULT OK");
            System.out.flush();
            System.exit(0);
        } catch (Throwable t) {
            Throwable cause = unwrap(t);
            System.out.println("RESULT FAILED: " + cause);
            cause.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    // ---- commands ----

    private static void keepOnPhone() throws Exception {
        Object audio = audioService();
        Map<Integer, String> targets = targetStrategies(audio);
        if (targets.isEmpty()) {
            throw new IllegalStateException("no separate routing strategy found for TalkBack or notifications");
        }
        List<Object> speakerOnly = Collections.singletonList(speaker());
        Method set = method(audio, "setPreferredDevicesForStrategy", 2);
        int failures = 0;
        for (Map.Entry<Integer, String> target : targets.entrySet()) {
            int result = (Integer) set.invoke(audio, target.getKey(), speakerOnly);
            System.out.println("strategy " + target.getKey() + " " + target.getValue() + " -> phone speaker: "
                    + (result == AUDIO_SYSTEM_SUCCESS ? "ok" : "error " + result));
            if (result != AUDIO_SYSTEM_SUCCESS) {
                failures++;
            }
        }
        if (failures > 0) {
            throw new IllegalStateException(failures + " of " + targets.size() + " strategies could not be changed");
        }

        // Android normally lets only one media app hold audio focus: a second app that starts
        // playing makes the first one pause. In multi-focus mode both keep playing, so music on
        // the speaker and a video on the phone can run at the same time. Calls and ringtones
        // still interrupt. Android saves this setting and restores it after a reboot.
        setMultiAudioFocus(audio, true);
    }

    static void setMultiAudioFocus(Object audio, boolean enabled) throws Exception {
        method(audio, "setMultiAudioFocusEnabled", 1).invoke(audio, enabled);
        System.out.println("apps keep playing side by side (multi audio focus): " + (enabled ? "on" : "off"));
    }

    private static void release() throws Exception {
        Object audio = audioService();
        Method get = method(audio, "getPreferredDevicesForStrategy", 1);
        Method remove = method(audio, "removePreferredDevicesForStrategy", 1);
        for (Map.Entry<Integer, String> target : targetStrategies(audio).entrySet()) {
            List<?> current = (List<?>) get.invoke(audio, target.getKey());
            String label = "strategy " + target.getKey() + " " + target.getValue();
            if (current == null || current.isEmpty()) {
                System.out.println(label + ": nothing to undo");
            } else if (!isOnlySpeaker(current)) {
                // Not what keep-on-phone sets, so someone else set it: leave it alone
                System.out.println(label + ": left alone, set by something else: " + current);
            } else {
                int result = (Integer) remove.invoke(audio, target.getKey());
                System.out.println(label + ": back to normal routing: "
                        + (result == AUDIO_SYSTEM_SUCCESS ? "ok" : "error " + result));
            }
        }
        // Back to Android's normal rule. Media that is playing pauses once, as after unplugging headphones.
        setMultiAudioFocus(audio, false);
    }

    /** Best effort: one failing section must not hide the others. */
    private static void status() throws Exception {
        System.out.println("Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        try {
            // As root, Bluetooth devices are listed and their addresses are not anonymised
            printOutputDevices("Output devices seen as root");
        } catch (Throwable t) {
            System.out.println("Output devices: unavailable (" + unwrap(t) + ")");
        }

        Object audio = audioService();
        try {
            Method getPreferred = method(audio, "getPreferredDevicesForStrategy", 1);
            List<Object> strategies = strategies(audio);
            System.out.println("Strategies (" + strategies.size() + "):");
            for (Object strategy : strategies) {
                StringBuilder usages = new StringBuilder();
                for (int usage : REPORTED_USAGES) {
                    if (supports(strategy, usage)) {
                        usages.append(' ').append(usageName(usage));
                    }
                }
                String preferred;
                try {
                    preferred = String.valueOf(getPreferred.invoke(audio, id(strategy)));
                } catch (Throwable t) {
                    preferred = "unavailable (" + unwrap(t) + ")";
                }
                System.out.println("  " + id(strategy) + " " + name(strategy)
                        + " usages[" + usages.toString().trim() + "] preferred " + preferred);
            }
            System.out.println("Would keep on phone: " + targetStrategies(audio));
        } catch (Throwable t) {
            System.out.println("Strategies: unavailable (" + unwrap(t) + ")");
        }

        try {
            Method devicesFor = method(audio, "getDevicesForAttributes", 1);
            System.out.println("Routed right now:");
            for (int usage : REPORTED_USAGES) {
                Object routed = devicesFor.invoke(audio, attributes(usage));
                System.out.println("  " + usageName(usage) + " -> " + routed);
            }
        } catch (Throwable t) {
            System.out.println("Routed right now: unavailable (" + unwrap(t) + ")");
        }
    }

    private static void printOutputDevices(String title) throws Exception {
        AudioDeviceInfo[] devices = outputDevices();
        System.out.println(title + ", uid " + android.os.Process.myUid() + " (" + devices.length + "):");
        for (AudioDeviceInfo info : devices) {
            System.out.println("  type " + info.getType() + " id " + info.getId()
                    + " name " + info.getProductName() + " address " + info.getAddress());
        }
    }

    /** The connected output devices, fresh from the audio service (no per-process cache). */
    static AudioDeviceInfo[] outputDevices() throws Exception {
        Object[] devices = (Object[]) Class.forName("android.media.AudioManager")
                .getMethod("getDevicesStatic", int.class)
                .invoke(null, GET_DEVICES_OUTPUTS);
        AudioDeviceInfo[] result = new AudioDeviceInfo[devices.length];
        for (int i = 0; i < devices.length; i++) {
            result[i] = (AudioDeviceInfo) devices[i];
        }
        return result;
    }

    private static String usageName(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_MEDIA:
                return "media";
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                return "accessibility";
            case AudioAttributes.USAGE_NOTIFICATION:
                return "notification";
            case AudioAttributes.USAGE_NOTIFICATION_RINGTONE:
                return "ringtone";
            case AudioAttributes.USAGE_ALARM:
                return "alarm";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION:
                return "call";
            default:
                return "usage" + usage;
        }
    }

    // ---- strategies ----

    /** Strategies to route to the phone: those for PHONE_USAGES, minus any that also carry music or calls. */
    private static Map<Integer, String> targetStrategies(Object audio) throws Exception {
        return selectStrategies(audio, PHONE_USAGES, PROTECTED_USAGES);
    }

    /** Strategies that carry any of the include usages and none of the exclude usages, as id -> name. */
    static Map<Integer, String> selectStrategies(Object audio, int[] include, int[] exclude) throws Exception {
        Map<Integer, String> targets = new LinkedHashMap<>();
        for (Object strategy : strategies(audio)) {
            if (!supportsAny(strategy, include)) {
                continue;
            }
            if (supportsAny(strategy, exclude)) {
                System.out.println("skipped strategy " + id(strategy) + " " + name(strategy)
                        + ": it also carries sounds that are left alone");
                continue;
            }
            targets.put(id(strategy), name(strategy));
        }
        return targets;
    }

    /**
     * Android 17 dropped the static AudioProductStrategy.getAudioProductStrategies(). The audio
     * service's own getAudioProductStrategies() (what the system API AudioManager uses) comes
     * first; the static methods remain as fallbacks for other versions.
     */
    static List<Object> strategies(Object audio) throws Exception {
        List<String> failures = new ArrayList<>();
        try {
            return asList(method(audio, "getAudioProductStrategies", 0).invoke(audio));
        } catch (Throwable t) {
            failures.add("IAudioService: " + unwrap(t));
        }
        try {
            return asList(Class.forName("android.media.AudioManager")
                    .getMethod("getAudioProductStrategies").invoke(null));
        } catch (Throwable t) {
            failures.add("AudioManager: " + unwrap(t));
        }
        try {
            return asList(Class.forName("android.media.audiopolicy.AudioProductStrategy")
                    .getMethod("getAudioProductStrategies").invoke(null));
        } catch (Throwable t) {
            failures.add("AudioProductStrategy: " + unwrap(t));
        }
        throw new IllegalStateException("cannot list audio strategies: " + failures);
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            throw new IllegalStateException("null strategy list");
        }
        return new ArrayList<>((List<?>) value);
    }

    static boolean supportsAny(Object strategy, int[] usages) throws Exception {
        for (int usage : usages) {
            if (supports(strategy, usage)) {
                return true;
            }
        }
        return false;
    }

    static boolean supports(Object strategy, int usage) throws Exception {
        try {
            return (Boolean) strategy.getClass()
                    .getMethod("supportsAudioAttributes", AudioAttributes.class)
                    .invoke(strategy, attributes(usage));
        } catch (NoSuchMethodException e) {
            // Fallback if the method is ever renamed: compare with the strategy's main attributes
            AudioAttributes main = (AudioAttributes) strategy.getClass()
                    .getMethod("getAudioAttributes").invoke(strategy);
            return main != null && main.getUsage() == usage;
        }
    }

    static int id(Object strategy) throws Exception {
        return (Integer) strategy.getClass().getMethod("getId").invoke(strategy);
    }

    static String name(Object strategy) {
        try {
            return String.valueOf(strategy.getClass().getMethod("getName").invoke(strategy));
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---- framework access ----

    static AudioAttributes attributes(int usage) {
        return new AudioAttributes.Builder().setUsage(usage).build();
    }

    private static Object speaker() throws Exception {
        return deviceAttributes(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");
    }

    private static boolean isOnlySpeaker(List<?> devices) throws Exception {
        return isSingleDeviceOfType(devices, new int[] {AudioDeviceInfo.TYPE_BUILTIN_SPEAKER});
    }

    /** True when the list holds exactly one AudioDeviceAttributes, of one of these AudioDeviceInfo types. */
    static boolean isSingleDeviceOfType(List<?> devices, int[] types) throws Exception {
        if (devices.size() != 1) {
            return false;
        }
        Object device = devices.get(0);
        int type = (Integer) device.getClass().getMethod("getType").invoke(device);
        for (int allowed : types) {
            if (type == allowed) {
                return true;
            }
        }
        return false;
    }

    /** An output AudioDeviceAttributes for an AudioDeviceInfo type and address. */
    static Object deviceAttributes(int type, String address) throws Exception {
        Constructor<?> constructor = Class.forName("android.media.AudioDeviceAttributes")
                .getConstructor(int.class, int.class, String.class);
        return constructor.newInstance(ROLE_OUTPUT, type, address == null ? "" : address);
    }

    static Object audioService() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "audio");
        if (binder == null) {
            throw new IllegalStateException("audio service not found");
        }
        return Class.forName("android.media.IAudioService$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    /**
     * Looks a method up on the public IAudioService interface, by name and parameter count, so
     * small signature changes between Android versions do not break it. Looking it up on the
     * service object itself would find the private Stub.Proxy class, which reflection may not call.
     */
    static Method method(Object service, String name, int parameterCount) throws Exception {
        Class<?> api = Class.forName("android.media.IAudioService");
        for (Method method : api.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == parameterCount) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + " with " + parameterCount + " parameters on " + service.getClass());
    }

    static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
