package nl.pim16aap2.bigDoors.compatibility;

import nl.pim16aap2.bigDoors.BigDoors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;

import org.jetbrains.annotations.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Permission-bearing fake players scoped to a single protection check, rather than an online session. */
final class OfflinePlayerPermissions
{
    private OfflinePlayerPermissions() {}

    static final class Scope implements AutoCloseable
    {
        final Function<String, @Nullable Boolean> permissions;
        private final Runnable cleanup;
        private boolean closed;

        Scope(Function<String, @Nullable Boolean> permissions, Runnable cleanup)
        {
            this.permissions = permissions;
            this.cleanup = cleanup;
        }

        @Override
        public synchronized void close()
        {
            if (closed) return;
            closed = true;
            cleanup.run();
        }
    }

    static CompletableFuture<Scope> prepare(
        BigDoors plugin, OfflinePlayer player, Location location)
    {
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
            return LuckPermsOfflinePermissions.load(player.getUniqueId(), location.getWorld().getName());

        // Without an offline permission API, only Bukkit defaults are available. Do not block a region thread
        // waiting for Vault lookups or grant permission overrides that cannot be verified.
        return CompletableFuture.completedFuture(new Scope(node -> null, () -> {}));
    }

    static Player wrap(Player fakePlayer, Scope scope)
    {
        final Player shell = CraftPlayerPermissionShell.tryCreate(fakePlayer, scope);
        if (shell != null)
            return shell;
        return newProxy(fakePlayer, scope);
    }

    /** LuckPerms answer for one node, or the Bukkit default when LuckPerms has no value. */
    static Object permissionAnswer(Scope scope, Player fakePlayer, String name, Object argument)
    {
        final String node = argument instanceof Permission ? ((Permission) argument).getName() : (String) argument;
        final Boolean value = scope.permissions.apply(node);
        if (name.equals("isPermissionSet"))
            return value != null;
        if (value != null)
            return value;
        final Permission permission = argument instanceof Permission ? (Permission) argument :
            Bukkit.getPluginManager().getPermission(node);
        return permission != null && permission.getDefault().getValue(fakePlayer.isOp());
    }

    private static Player newProxy(Player fakePlayer, Scope scope)
    {
        return (Player) Proxy.newProxyInstance(IFakePlayer.class.getClassLoader(),
            new Class<?>[]{Player.class, IFakePlayer.class, AutoCloseable.class}, (proxy, method, args) ->
            {
                final String name = method.getName();
                if (name.equals("close") && method.getParameterCount() == 0)
                {
                    scope.close();
                    return null;
                }
                if ((name.equals("hasPermission") || name.equals("isPermissionSet")) && args.length == 1)
                    return permissionAnswer(scope, fakePlayer, name, args[0]);
                if (name.equals("getPlayer") && method.getParameterCount() == 0)
                    return proxy;
                if (name.equals("equals") && args.length == 1)
                    return proxy == args[0];
                if (name.equals("hashCode") && method.getParameterCount() == 0)
                    return System.identityHashCode(proxy);
                try
                {
                    return method.invoke(fakePlayer, args);
                }
                catch (InvocationTargetException exception)
                {
                    throw exception.getCause();
                }
            });
    }
}
