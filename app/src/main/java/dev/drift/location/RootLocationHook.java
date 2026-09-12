package dev.drift.location;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed entry point. This class is loaded only inside the explicitly selected target app.
 */
public final class RootLocationHook implements IXposedHookLoadPackage {
    private static final String TARGET_WECHAT = LocationContract.TARGET_WECHAT;
    private static final String SYSTEM_FRAMEWORK = "android";

    // The public Tencent Location SDK uses the first package. Some WeChat builds bundle
    // a relocated SAPP copy, so keep the second package as a compatibility fallback.
    private static final String[] TENCENT_PREFIXES = {
            "com.tencent.map.geolocation.",
            "com.tencent.map.geolocation.sapp."
    };
    private static final String TENCENT_MANAGER = "TencentLocationManager";
    private static final String TENCENT_LOCATION = "TencentLocation";
    private static final String TENCENT_LISTENER = "TencentLocationListener";
    private static final long RELOAD_INTERVAL_MS = 200L;
    private static final long SYNTHETIC_CALLBACK_INTERVAL_MS = 1_000L;
    private static final long PUSHED_STATE_TIMEOUT_MS = 2_500L;

    private static final Set<Class<?>> HOOKED_TENCENT_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Class<?>> HOOKED_TENCENT_MANAGERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Class<?>> HOOKED_SYSTEM_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Object, Object> LISTENER_WRAPPERS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Long> LAST_SYNTHETIC_CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static XSharedPreferences preferences;
    private static volatile long lastReloadAt;
    private static volatile boolean classLoaderHookInstalled;
    private static volatile boolean stateReceiverHookInstalled;
    private static volatile boolean stateReceiverRegistered;
    private static volatile ClassLoader targetClassLoader;
    private static volatile RootState pushedState = RootState.OFF;
    private static volatile long pushedStateAt;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        boolean isWechat = TARGET_WECHAT.equals(loadPackageParam.packageName);
        boolean isSystemFramework = SYSTEM_FRAMEWORK.equals(loadPackageParam.packageName);
        if (!isWechat && !isSystemFramework) return;

        try {
            targetClassLoader = loadPackageParam.classLoader;
            preferences = new XSharedPreferences("dev.drift.location", LocationContract.PREFS);
            preferences.makeWorldReadable();
            preferences.reload();
            if (isSystemFramework) {
                hookSystemLocation(loadPackageParam.classLoader);
                XposedBridge.log("DriftRoot: system location hooks active");
                return;
            }
            hookAndroidLocation();
            installStateReceiverHook();
            installClassLoaderHook();
            hookTencentLocation(loadPackageParam.classLoader);
            XposedBridge.log("DriftRoot: hooks active in " + loadPackageParam.processName);
        } catch (Throwable throwable) {
            XposedBridge.log("DriftRoot: initialization failed in " + loadPackageParam.processName);
            XposedBridge.log(throwable);
        }
    }

    private static void hookSystemLocation(ClassLoader classLoader) {
        Class<?> serviceClass = findFirstClass(classLoader,
                "com.android.server.LocationManagerService",
                "com.android.server.location.LocationManagerService");
        if (serviceClass == null || !HOOKED_SYSTEM_CLASSES.add(serviceClass)) {
            XposedBridge.log("DriftRoot: Android LocationManagerService unavailable");
            return;
        }

        XposedBridge.hookAllMethods(serviceClass, "systemRunning", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = findServiceContext(param.thisObject);
                if (context != null) registerStateReceiver(context);
            }
        });

        XposedBridge.hookAllMethods(serviceClass, "getLastLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!readState().active) return;
                Object result = param.getResult();
                if (result == null || result instanceof Location) {
                    param.setResult(createFakeAndroidLocation((Location) result));
                }
            }
        });

        XC_MethodHook replaceReportedLocations = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!readState().active) return;
                replaceLocationArguments(param.args);
            }
        };
        XposedBridge.hookAllMethods(serviceClass, "reportLocation", replaceReportedLocations);
        XposedBridge.hookAllMethods(serviceClass, "reportLocationBatch", replaceReportedLocations);

        Class<?> receiverClass = findFirstClass(classLoader,
                "com.android.server.LocationManagerService$Receiver",
                "com.android.server.location.LocationManagerService$Receiver");
        if (receiverClass != null && HOOKED_SYSTEM_CLASSES.add(receiverClass)) {
            XposedBridge.hookAllMethods(receiverClass, "callLocationChangedLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!readState().active) return;
                    replaceLocationArguments(param.args);
                }
            });
        }
    }

    private static Class<?> findFirstClass(ClassLoader classLoader, String... names) {
        for (String name : names) {
            Class<?> type = XposedHelpers.findClassIfExists(name, classLoader);
            if (type != null) return type;
        }
        return null;
    }

    private static Context findServiceContext(Object service) {
        for (Class<?> type = service == null ? null : service.getClass();
             type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mContext");
                field.setAccessible(true);
                Object value = field.get(service);
                if (value instanceof Context) return (Context) value;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void replaceLocationArguments(Object[] args) {
        if (args == null) return;
        for (int index = 0; index < args.length; index++) {
            Object argument = args[index];
            if (argument instanceof Location) {
                args[index] = createFakeAndroidLocation((Location) argument);
            } else if (argument instanceof List<?>) {
                List<?> source = (List<?>) argument;
                ArrayList<Object> replacement = new ArrayList<>(source.size());
                boolean containedLocation = false;
                for (Object item : source) {
                    if (item instanceof Location) {
                        replacement.add(createFakeAndroidLocation((Location) item));
                        containedLocation = true;
                    } else {
                        replacement.add(item);
                    }
                }
                if (containedLocation) args[index] = replacement;
            }
        }
    }

    private static Location createFakeAndroidLocation(Location original) {
        RootState state = readState();
        String provider = original == null || original.getProvider() == null
                ? "gps" : original.getProvider();
        Location location = original == null ? new Location(provider) : new Location(original);
        location.setLatitude(state.latitude);
        location.setLongitude(state.longitude);
        location.setAltitude(12.0d);
        location.setAccuracy(5.0f);
        location.setSpeed(state.speed);
        location.setBearing(state.bearing);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        Bundle extras = location.getExtras();
        if (extras == null) extras = new Bundle();
        extras.remove("mockLocation");
        extras.remove("mock_location");
        extras.remove("isMock");
        location.setExtras(extras);
        clearMockFlag(location);
        return location;
    }

    private static void clearMockFlag(Location location) {
        try {
            Method method = Location.class.getDeclaredMethod("setIsFromMockProvider", boolean.class);
            method.setAccessible(true);
            method.invoke(location, false);
            return;
        } catch (Throwable ignored) {
        }
        for (String fieldName : new String[]{"mIsFromMockProvider", "mMock"}) {
            try {
                Field field = Location.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setBoolean(location, false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void installStateReceiverHook() {
        if (stateReceiverHookInstalled) return;
        synchronized (RootLocationHook.class) {
            if (stateReceiverHookInstalled) return;
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0
                            && param.args[0] instanceof Context) {
                        registerStateReceiver((Context) param.args[0]);
                    }
                }
            });
            stateReceiverHookInstalled = true;
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API 25-32 has no receiver flag overload.
    private static void registerStateReceiver(Context context) {
        if (stateReceiverRegistered) return;
        synchronized (RootLocationHook.class) {
            if (stateReceiverRegistered) return;
            Context appContext = context.getApplicationContext();
            if (appContext == null) appContext = context;
            IntentFilter filter = new IntentFilter(LocationContract.ACTION_ROOT_STATE);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    if (intent == null || !LocationContract.ACTION_ROOT_STATE.equals(intent.getAction())) return;
                    if (!LocationContract.ROOT_BROADCAST_TOKEN.equals(
                            intent.getStringExtra(LocationContract.EXTRA_ROOT_TOKEN))) return;
                    boolean active = intent.getBooleanExtra(LocationContract.EXTRA_RUNNING, false);
                    pushedState = active
                            ? new RootState(
                            true,
                            intent.getDoubleExtra(LocationContract.EXTRA_LATITUDE,
                                    LocationContract.DEFAULT_LATITUDE),
                            intent.getDoubleExtra(LocationContract.EXTRA_LONGITUDE,
                                    LocationContract.DEFAULT_LONGITUDE),
                            intent.getFloatExtra(LocationContract.EXTRA_SPEED,
                                    LocationContract.DEFAULT_SPEED),
                            intent.getFloatExtra(LocationContract.EXTRA_BEARING, 0f))
                            : RootState.OFF;
                    pushedStateAt = SystemClock.elapsedRealtime();
                }
            };
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    appContext.registerReceiver(receiver, filter);
                }
                stateReceiverRegistered = true;
                XposedBridge.log("DriftRoot: live state receiver registered");
            } catch (Throwable throwable) {
                XposedBridge.log("DriftRoot: live state receiver registration failed");
                XposedBridge.log(throwable);
            }
        }
    }

    private static void hookAndroidLocation() {
        XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!readState().active) return;
                        Object result = param.getResult();
                        if (result == null || result instanceof Location) {
                            param.setResult(createFakeAndroidLocation((Location) result));
                        }
                    }
                });

        hookResult(Location.class, "getLatitude", state -> state.latitude);
        hookResult(Location.class, "getLongitude", state -> state.longitude);
        hookResult(Location.class, "getAltitude", state -> 12.0d);
        hookResult(Location.class, "getAccuracy", state -> 5.0f);
        hookResult(Location.class, "getSpeed", state -> state.speed);
        hookResult(Location.class, "getBearing", state -> state.bearing);
        hookResult(Location.class, "getProvider", state -> "gps");
        hookResult(Location.class, "getTime", state -> System.currentTimeMillis());
        hookResult(Location.class, "getElapsedRealtimeNanos",
                state -> SystemClock.elapsedRealtimeNanos());
        hookResult(Location.class, "hasAccuracy", state -> true);
        hookResult(Location.class, "hasSpeed", state -> true);
        hookResult(Location.class, "hasBearing", state -> true);
        hookResult(Location.class, "isFromMockProvider", state -> false);
        hookResult(Location.class, "isMock", state -> false);

        // These methods only exist on newer Android releases. hookAllMethods simply finds
        // no method on Android 7.1.2, while the same code works when the target is newer.
        hookResult(Location.class, "getSpeedAccuracyMetersPerSecond", state -> 0.2f);
        hookResult(Location.class, "getBearingAccuracyDegrees", state -> 2.0f);
        hookResult(Location.class, "getVerticalAccuracyMeters", state -> 3.0f);
        hookResult(Location.class, "hasSpeedAccuracy", state -> true);
        hookResult(Location.class, "hasBearingAccuracy", state -> true);
        hookResult(Location.class, "hasVerticalAccuracy", state -> true);

        XposedBridge.hookAllMethods(Location.class, "getExtras", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!readState().active) return;
                Bundle extras = (Bundle) param.getResult();
                if (extras == null) {
                    extras = new Bundle();
                    param.setResult(extras);
                }
                extras.remove("mockLocation");
                extras.remove("mock_location");
                extras.remove("isMock");
            }
        });
    }

    private static void installClassLoaderHook() {
        if (classLoaderHookInstalled) return;
        synchronized (RootLocationHook.class) {
            if (classLoaderHookInstalled) return;
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof String)) return;
                    String name = (String) param.args[0];
                    if (!isTencentClassName(name)) return;
                    Object result = param.getResult();
                    if (result instanceof Class<?>) {
                        ClassLoader loader = param.thisObject instanceof ClassLoader
                                ? (ClassLoader) param.thisObject : targetClassLoader;
                        hookLoadedTencentClass((Class<?>) result, loader);
                    }
                }
            });
            classLoaderHookInstalled = true;
        }
    }

    private static boolean isTencentClassName(String name) {
        for (String prefix : TENCENT_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private static void hookTencentLocation(ClassLoader classLoader) {
        if (classLoader == null) return;
        for (String prefix : TENCENT_PREFIXES) {
            Class<?> managerClass = XposedHelpers.findClassIfExists(
                    prefix + TENCENT_MANAGER, classLoader);
            if (managerClass != null) hookManagerClass(managerClass, classLoader);

            Class<?> locationClass = XposedHelpers.findClassIfExists(
                    prefix + TENCENT_LOCATION, classLoader);
            if (locationClass != null) hookTencentGetterClass(locationClass);
        }
    }

    private static void hookLoadedTencentClass(Class<?> type, ClassLoader classLoader) {
        String name = type.getName();
        if (name.endsWith(TENCENT_MANAGER)) {
            hookManagerClass(type, classLoader == null ? type.getClassLoader() : classLoader);
        } else if (name.endsWith(TENCENT_LOCATION)) {
            hookTencentGetterClass(type);
        }
    }

    private static void hookManagerClass(Class<?> managerClass, ClassLoader classLoader) {
        if (!HOOKED_TENCENT_MANAGERS.add(managerClass)) return;

        XC_MethodHook requestHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                wrapListenerArguments(param, classLoader);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!readState().active) return;
                if (param.getResult() instanceof Integer && ((Integer) param.getResult()) != 0) {
                    // Tencent returns non-zero when its native provider cannot start. In
                    // Root-only mode the callback is supplied locally below.
                    param.setResult(0);
                }
                Object listener = findListenerArgument(param);
                if (listener != null) scheduleSyntheticCallback(listener, classLoader);
            }
        };
        XposedBridge.hookAllMethods(managerClass, "requestLocationUpdates", requestHook);
        XposedBridge.hookAllMethods(managerClass, "requestSingleFreshLocation", requestHook);

        XposedBridge.hookAllMethods(managerClass, "removeUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null) return;
                for (int index = 0; index < param.args.length; index++) {
                    Object wrapper = LISTENER_WRAPPERS.get(param.args[index]);
                    if (wrapper != null) param.args[index] = wrapper;
                }
            }
        });

        XposedBridge.hookAllMethods(managerClass, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                RootState state = readState();
                if (!state.active) return;
                Object location = param.getResult();
                if (location != null) {
                    hookTencentGetterClass(location.getClass());
                } else if (param.method instanceof Method) {
                    Class<?> returnType = ((Method) param.method).getReturnType();
                    if (returnType.isInterface()) {
                        param.setResult(fakeTencentLocation(returnType, classLoader));
                    }
                }
            }
        });
    }

    private static void wrapListenerArguments(XC_MethodHook.MethodHookParam param, ClassLoader classLoader) {
        if (param.args == null || !(param.method instanceof Method)) return;
        Class<?>[] parameterTypes = ((Method) param.method).getParameterTypes();
        for (int index = 0; index < param.args.length; index++) {
            Object argument = param.args[index];
            if (argument == null) continue;
            Class<?> parameterType = index < parameterTypes.length ? parameterTypes[index] : null;
            if (isListenerType(parameterType) || isListenerObject(argument)) {
                param.args[index] = listenerWrapper(argument, parameterType, classLoader);
            }
        }
    }

    private static Object findListenerArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || !(param.method instanceof Method)) return null;
        Class<?>[] parameterTypes = ((Method) param.method).getParameterTypes();
        for (int index = 0; index < param.args.length; index++) {
            Object argument = param.args[index];
            Class<?> parameterType = index < parameterTypes.length ? parameterTypes[index] : null;
            if (argument != null && (isListenerType(parameterType) || isListenerObject(argument))) {
                return argument;
            }
        }
        return null;
    }

    private static boolean isListenerType(Class<?> type) {
        return type != null && type.getName().endsWith(TENCENT_LISTENER);
    }

    private static boolean isListenerObject(Object object) {
        if (object == null) return false;
        for (Class<?> type : object.getClass().getInterfaces()) {
            if (isListenerType(type)) return true;
        }
        return false;
    }

    private static Object listenerWrapper(Object original, Class<?> listenerType, ClassLoader classLoader) {
        Object existing = LISTENER_WRAPPERS.get(original);
        if (existing != null) return existing;

        Class<?> proxyType = listenerType;
        if (proxyType == null || !proxyType.isInterface()) {
            proxyType = findListenerInterface(original.getClass());
        }
        if (proxyType == null) return original;

        ClassLoader proxyLoader = proxyType.getClassLoader() == null
                ? classLoader : proxyType.getClassLoader();
        Object wrapper = Proxy.newProxyInstance(proxyLoader, new Class<?>[]{proxyType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, original, method, args);
                    }
                    if ("onLocationChanged".equals(method.getName())) {
                        prepareLocationCallback(method, args, classLoader);
                    } else if ("onStatusUpdate".equals(method.getName())) {
                        prepareStatusCallback(args, method.getParameterTypes());
                    }
                    try {
                        return method.invoke(original, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        LISTENER_WRAPPERS.put(original, wrapper);
        return wrapper;
    }

    private static Class<?> findListenerInterface(Class<?> type) {
        for (Class<?> candidate : type.getInterfaces()) {
            if (isListenerType(candidate)) return candidate;
        }
        return null;
    }

    private static void prepareLocationCallback(Method method, Object[] args, ClassLoader classLoader) {
        RootState state = readState();
        if (!state.active || args == null || args.length == 0) return;

        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<?> locationType = parameterTypes[0];
        if (args[0] == null && locationType.isInterface()) {
            args[0] = fakeTencentLocation(locationType, classLoader);
        } else if (args[0] != null) {
            hookTencentGetterClass(args[0].getClass());
        }

        // TencentLocationListener.onLocationChanged(location, error, reason)
        if (args.length > 1 && parameterTypes.length > 1
                && (parameterTypes[1] == int.class || parameterTypes[1] == Integer.class)) {
            args[1] = 0;
        }
        if (args.length > 2 && parameterTypes.length > 2 && parameterTypes[2] == String.class) {
            args[2] = "";
        }
    }

    private static void prepareStatusCallback(Object[] args, Class<?>[] parameterTypes) {
        if (!readState().active || args == null) return;
        // A Root-only session has no physical GPS provider. Report the provider as enabled
        // so Tencent's client does not discard the following synthetic location callback.
        if (args.length > 0 && parameterTypes.length > 0 && parameterTypes[0] == String.class
                && args[0] == null) {
            args[0] = "gps";
        }
        if (args.length > 1 && parameterTypes.length > 1
                && (parameterTypes[1] == int.class || parameterTypes[1] == Integer.class)) {
            args[1] = 1;
        }
        if (args.length > 2 && parameterTypes.length > 2 && parameterTypes[2] == String.class) {
            args[2] = "";
        }
    }

    private static void scheduleSyntheticCallback(Object listener, ClassLoader classLoader) {
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_SYNTHETIC_CALLBACKS) {
            Long last = LAST_SYNTHETIC_CALLBACKS.get(listener);
            if (last != null && now - last < SYNTHETIC_CALLBACK_INTERVAL_MS) return;
            LAST_SYNTHETIC_CALLBACKS.put(listener, now);
        }

        Looper looper = Looper.myLooper();
        Handler handler = new Handler(looper == null ? Looper.getMainLooper() : looper);
        handler.post(() -> deliverSyntheticCallback(listener, classLoader));
    }

    private static void deliverSyntheticCallback(Object listener, ClassLoader classLoader) {
        if (!readState().active) return;
        deliverSyntheticStatus(listener);
        for (Class<?> type = listener.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"onLocationChanged".equals(method.getName())) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 0) continue;
                Object[] args = new Object[parameterTypes.length];
                if (parameterTypes[0].isInterface()) {
                    args[0] = fakeTencentLocation(parameterTypes[0], classLoader);
                }
                for (int index = 1; index < parameterTypes.length; index++) {
                    if (parameterTypes[index] == int.class || parameterTypes[index] == Integer.class) {
                        args[index] = 0;
                    } else if (parameterTypes[index] == String.class) {
                        args[index] = "";
                    }
                }
                try {
                    method.setAccessible(true);
                    method.invoke(listener, args);
                } catch (Throwable throwable) {
                    XposedBridge.log("DriftRoot: synthetic Tencent callback failed");
                }
                return;
            }
        }
    }

    private static void deliverSyntheticStatus(Object listener) {
        for (Class<?> type = listener.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"onStatusUpdate".equals(method.getName())) continue;
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length < 2) continue;
                Object[] args = new Object[parameterTypes.length];
                if (parameterTypes[0] == String.class) args[0] = "gps";
                if (parameterTypes[1] == int.class || parameterTypes[1] == Integer.class) args[1] = 1;
                if (parameterTypes.length > 2 && parameterTypes[2] == String.class) args[2] = "";
                try {
                    method.setAccessible(true);
                    method.invoke(listener, args);
                } catch (Throwable throwable) {
                    XposedBridge.log("DriftRoot: synthetic Tencent status callback failed");
                }
                return;
            }
        }
    }

    private static Object fakeTencentLocation(Class<?> locationType, ClassLoader classLoader) {
        if (!locationType.isInterface()) return null;
        ClassLoader proxyLoader = locationType.getClassLoader() == null
                ? classLoader : locationType.getClassLoader();
        return Proxy.newProxyInstance(proxyLoader, new Class<?>[]{locationType},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, proxy, method, args);
                    }
                    return fakeLocationValue(method.getName(), method.getReturnType(), readState());
                });
    }

    private static Object objectMethod(Object proxy, Object original, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length > 0 && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return "DriftLocation(" + original + ")";
            default:
                return null;
        }
    }

    private static Object fakeLocationValue(String methodName, Class<?> returnType, RootState state) {
        if ("getLatitude".equals(methodName)) return state.latitude;
        if ("getLongitude".equals(methodName)) return state.longitude;
        if ("getAltitude".equals(methodName)) return 12.0d;
        if ("getAccuracy".equals(methodName)) return 5.0f;
        if ("getSpeed".equals(methodName)) return state.speed;
        if ("getBearing".equals(methodName)) return state.bearing;
        if ("getProvider".equals(methodName)) return "gps";
        if ("getTime".equals(methodName)) return System.currentTimeMillis();
        if ("getElapsedRealtimeNanos".equals(methodName)) return SystemClock.elapsedRealtimeNanos();
        if ("getElapsedRealtime".equals(methodName)) return SystemClock.elapsedRealtime();
        if ("getDirection".equals(methodName)) return (double) state.bearing;
        if ("getCoordinateType".equals(methodName)) return 0; // WGS84, same as the map input.
        if ("isMockGps".equals(methodName)) return 0;
        if (methodName.startsWith("isMock") || "isFromMockProvider".equals(methodName)) return false;
        if (methodName.startsWith("has")) return true;
        if (returnType == Bundle.class || "getExtras".equals(methodName)) return new Bundle();
        if (returnType == boolean.class || returnType == Boolean.class) return false;
        if (returnType == byte.class || returnType == Byte.class) return (byte) 0;
        if (returnType == short.class || returnType == Short.class) return (short) 0;
        if (returnType == int.class || returnType == Integer.class) return 0;
        if (returnType == long.class || returnType == Long.class) return 0L;
        if (returnType == float.class || returnType == Float.class) return 0.0f;
        if (returnType == double.class || returnType == Double.class) return 0.0d;
        if (returnType == char.class || returnType == Character.class) return '\0';
        return null;
    }

    private static void hookTencentGetterClass(Class<?> locationClass) {
        for (Class<?> type = locationClass; type != null && type != Object.class; type = type.getSuperclass()) {
            if (!HOOKED_TENCENT_CLASSES.add(type)) continue;
            hookResult(type, "getLatitude", state -> state.latitude);
            hookResult(type, "getLongitude", state -> state.longitude);
            hookResult(type, "getAltitude", state -> 12.0d);
            hookResult(type, "getAccuracy", state -> 5.0f);
            hookResult(type, "getSpeed", state -> state.speed);
            hookResult(type, "getBearing", state -> state.bearing);
            hookResult(type, "getProvider", state -> "gps");
            hookResult(type, "getTime", state -> System.currentTimeMillis());
            hookResult(type, "getElapsedRealtimeNanos", state -> SystemClock.elapsedRealtimeNanos());
            hookResult(type, "getElapsedRealtime", state -> SystemClock.elapsedRealtime());
            hookResult(type, "getCoordinateType", state -> 0);
            hookResult(type, "getDirection", state -> (double) state.bearing);
            hookResult(type, "isMockGps", state -> 0);
            hookResult(type, "isMock", state -> false);
            hookResult(type, "isFromMockProvider", state -> false);
        }
    }

    private static void hookResult(Class<?> type, String methodName, StateValue value) {
        try {
            XposedBridge.hookAllMethods(type, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    RootState state = readState();
                    if (state.active) param.setResult(value.get(state));
                }
            });
        } catch (Throwable throwable) {
            XposedBridge.log("DriftRoot: unable to hook " + type.getName() + "." + methodName);
        }
    }

    private static RootState readState() {
        long now = SystemClock.elapsedRealtime();
        if (pushedStateAt > 0 && now - pushedStateAt <= PUSHED_STATE_TIMEOUT_MS) {
            return pushedState;
        }
        XSharedPreferences current = preferences;
        if (current == null) return RootState.OFF;
        if (now - lastReloadAt >= RELOAD_INTERVAL_MS) {
            synchronized (RootLocationHook.class) {
                if (now - lastReloadAt >= RELOAD_INTERVAL_MS) {
                    current.reload();
                    lastReloadAt = now;
                }
            }
        }
        if (!current.getBoolean(LocationContract.KEY_RUNNING, false)) return RootState.OFF;
        double latitude = Double.longBitsToDouble(current.getLong(
                LocationContract.KEY_LATITUDE,
                Double.doubleToRawLongBits(LocationContract.DEFAULT_LATITUDE)));
        double longitude = Double.longBitsToDouble(current.getLong(
                LocationContract.KEY_LONGITUDE,
                Double.doubleToRawLongBits(LocationContract.DEFAULT_LONGITUDE)));
        return new RootState(true, latitude, longitude,
                current.getFloat(LocationContract.KEY_SPEED, LocationContract.DEFAULT_SPEED),
                current.getFloat(LocationContract.KEY_BEARING, 0f));
    }

    private interface StateValue {
        Object get(RootState state);
    }

    private static final class RootState {
        static final RootState OFF = new RootState(false, 0, 0, 0, 0);
        final boolean active;
        final double latitude;
        final double longitude;
        final float speed;
        final float bearing;

        RootState(boolean active, double latitude, double longitude, float speed, float bearing) {
            this.active = active;
            this.latitude = latitude;
            this.longitude = longitude;
            this.speed = speed;
            this.bearing = bearing;
        }
    }
}
