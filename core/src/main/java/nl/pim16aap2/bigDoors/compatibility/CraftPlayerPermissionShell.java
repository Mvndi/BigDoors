package nl.pim16aap2.bigDoors.compatibility;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.This;
import net.bytebuddy.matcher.ElementMatchers;
import nl.pim16aap2.bigDoors.BigDoors;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import static net.bytebuddy.matcher.ElementMatchers.isFinal;
import static net.bytebuddy.matcher.ElementMatchers.isNative;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Offline permission subject that is still a {@code CraftPlayer}.
 * <p>
 * Towny asks LuckPerms whether the door owner is an admin. LuckPerms reads the {@code perm} field declared on
 * {@code CraftHumanEntity}. A JDK proxy or a generated {@code Player} is not that class, so the read throws and Towny
 * aborts the protection check. This shell is a real subclass, so that read succeeds, while permission questions are
 * still answered from the offline LuckPerms snapshot.
 */
final class CraftPlayerPermissionShell
{
    private static final Object[] NO_ARGS = new Object[0];
    private static final Object LOCK = new Object();
    private static final Map<Object, Player> DELEGATES = new IdentityHashMap<>();
    private static final Map<Object, OfflinePlayerPermissions.Scope> SCOPES = new IdentityHashMap<>();

    private static volatile @Nullable Class<?> generated;
    private static volatile boolean unavailable;
    private static volatile boolean allocateFailed;

    private CraftPlayerPermissionShell() {}

    /** Build the shell class during enable so a failure is visible before the first door check. */
    static void prepare()
    {
        ensureClass();
    }

    static @Nullable Player tryCreate(Player delegate, OfflinePlayerPermissions.Scope scope)
    {
        final Class<?> type = ensureClass();
        if (type == null)
            return null;
        try
        {
            final Object instance = allocate(type);
            synchronized (LOCK)
            {
                DELEGATES.put(instance, delegate);
                SCOPES.put(instance, scope);
            }
            setEntityType(instance);
            return (Player) instance;
        }
        catch (Throwable throwable)
        {
            if (!allocateFailed)
            {
                allocateFailed = true;
                BigDoors.get().getMyLogger().log(
                    "Could not allocate an offline CraftPlayer. Towny checks will fall back to a proxy player.",
                    throwable);
            }
            return null;
        }
    }

    @RuntimeType
    public static Object intercept(
        @This Object self, @Origin Method method, @AllArguments Object[] args)
        throws Throwable
    {
        if (args == null)
            args = NO_ARGS;
        final String name = method.getName();
        final Player delegate;
        final OfflinePlayerPermissions.Scope scope;
        final boolean close = name.equals("close") && method.getParameterCount() == 0;
        synchronized (LOCK)
        {
            if (close)
            {
                delegate = DELEGATES.remove(self);
                scope = SCOPES.remove(self);
            }
            else
            {
                delegate = DELEGATES.get(self);
                scope = SCOPES.get(self);
            }
        }
        if (close)
        {
            if (scope != null)
                scope.close();
            return null;
        }
        if (delegate == null)
            return defaultValue(method.getReturnType());
        if (name.equals("getPlayer") && method.getParameterCount() == 0)
            return self;
        if (name.equals("equals") && args.length == 1)
            return self == args[0];
        if (name.equals("hashCode") && method.getParameterCount() == 0)
            return System.identityHashCode(self);
        if ((name.equals("hasPermission") || name.equals("isPermissionSet")) && args.length == 1 && scope != null)
            return OfflinePlayerPermissions.permissionAnswer(scope, delegate, name, args[0]);
        if (method.getParameterCount() == 0 && (name.equals("locale") || name.equals("getLocale")))
        {
            final Object value = forward(delegate, method, args);
            if (value != null)
                return value;
            if (method.getReturnType() == String.class)
                return "en_us";
            if (Locale.class.isAssignableFrom(method.getReturnType()))
                return Locale.ENGLISH;
            return defaultValue(method.getReturnType());
        }
        return forward(delegate, method, args);
    }

    private static @Nullable Class<?> ensureClass()
    {
        final Class<?> existing = generated;
        if (existing != null || unavailable)
            return existing;
        synchronized (LOCK)
        {
            if (generated != null || unavailable)
                return generated;
            final long started = System.nanoTime();
            try
            {
                final Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
                final ClassLoader loader = BigDoors.get().getBigDoorsClassLoader();
                try (DynamicType.Unloaded<?> unloaded = new ByteBuddy()
                    .subclass(craftPlayer, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .implement(IFakePlayer.class, AutoCloseable.class)
                    .name(CraftPlayerPermissionShell.class.getName() + "$Generated")
                    .method(ElementMatchers.any().and(not(isStatic())).and(not(isFinal())).and(not(isNative())).and(not(isPrivate())))
                    .intercept(MethodDelegation.to(CraftPlayerPermissionShell.class))
                    .make())
                {
                    generated = unloaded.load(loader, ClassLoadingStrategy.Default.INJECTION).getLoaded();
                }
                // Prove a skipped constructor still yields an instance LuckPerms can reflect on.
                allocate(generated);
                BigDoors.get().getMyLogger().info(String.format(
                    "Generated %s in %dms for offline Towny permission checks.",
                    generated.getName(), (System.nanoTime() - started) / 1_000_000));
            }
            catch (Throwable throwable)
            {
                unavailable = true;
                BigDoors.get().getMyLogger().log(
                    "Could not subclass CraftPlayer. Offline Towny checks may fail while LuckPerms is installed.",
                    throwable);
            }
            return generated;
        }
    }

    private static Object allocate(Class<?> type)
        throws Exception
    {
        try
        {
            final Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
            final Object factory = factoryClass.getMethod("getReflectionFactory").invoke(null);
            final Constructor<?> objectCtor = Object.class.getDeclaredConstructor();
            final Method factoryMethod = factoryClass.getMethod(
                "newConstructorForSerialization", Class.class, Constructor.class);
            final Constructor<?> ctor = (Constructor<?>) factoryMethod.invoke(factory, type, objectCtor);
            ctor.setAccessible(true);
            return ctor.newInstance();
        }
        catch (Throwable reflectionFailure)
        {
            try
            {
                final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                final Object unsafe = theUnsafe.get(null);
                return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
            }
            catch (Throwable unsafeFailure)
            {
                unsafeFailure.addSuppressed(reflectionFailure);
                if (unsafeFailure instanceof Exception)
                    throw (Exception) unsafeFailure;
                throw new IllegalStateException(unsafeFailure);
            }
        }
    }

    /** {@code getType()} is final and reads a field the skipped constructor never sets. */
    private static void setEntityType(Object instance)
    {
        try
        {
            Class<?> type = instance.getClass();
            Field field = null;
            while (type != null && field == null)
            {
                try
                {
                    field = type.getDeclaredField("entityType");
                }
                catch (NoSuchFieldException ignored)
                {
                    type = type.getSuperclass();
                }
            }
            if (field == null || !EntityType.class.isAssignableFrom(field.getType()))
                return;
            final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            final Object unsafe = theUnsafe.get(null);
            final long offset = (long) unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
            unsafeClass.getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, instance, offset, EntityType.PLAYER);
        }
        catch (Throwable ignored)
        {
            // getType() then returns null. Towny's permission check does not need it.
        }
    }

    private static Object forward(Player delegate, Method method, Object[] args)
        throws Throwable
    {
        final Method target;
        try
        {
            target = delegate.getClass().getMethod(method.getName(), method.getParameterTypes());
        }
        catch (NoSuchMethodException exception)
        {
            return defaultValue(method.getReturnType());
        }
        try
        {
            return target.invoke(delegate, args);
        }
        catch (InvocationTargetException exception)
        {
            final Throwable cause = exception.getCause();
            throw cause == null ? exception : cause;
        }
    }

    private static @Nullable Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive())
            return null;
        if (type == boolean.class)
            return false;
        if (type == int.class)
            return 0;
        if (type == long.class)
            return 0L;
        if (type == double.class)
            return 0D;
        if (type == float.class)
            return 0F;
        if (type == short.class)
            return (short) 0;
        if (type == byte.class)
            return (byte) 0;
        if (type == char.class)
            return '\0';
        return null;
    }
}
