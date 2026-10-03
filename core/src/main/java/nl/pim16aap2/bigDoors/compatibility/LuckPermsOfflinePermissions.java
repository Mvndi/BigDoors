package nl.pim16aap2.bigDoors.compatibility;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.permissions.TownyPerms;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.query.QueryOptions;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import net.luckperms.api.util.Tristate;
import net.luckperms.api.model.data.DataMutateResult;
import org.bukkit.Bukkit;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Kept separate so BigDoors still loads when LuckPerms is not installed. */
final class LuckPermsOfflinePermissions
{
    // Reference counts prevent simultaneous old/new-location checks from removing each other's grants.
    private static final Map<User, Map<Node, Grant>> GRANTS = new IdentityHashMap<>();
    private static final class Grant
    {
        int users = 1;
        final boolean added;
        Grant(boolean added) { this.added = added; }
    }

    private LuckPermsOfflinePermissions() {}

    static CompletableFuture<OfflinePlayerPermissions.Scope> load(UUID uuid, String world)
    {
        final LuckPerms luckPerms = LuckPermsProvider.get();
        final ImmutableContextSet contexts = ImmutableContextSet.builder()
            .addAll(luckPerms.getContextManager().getStaticContext()).add("world", world).build();
        final QueryOptions options = luckPerms.getContextManager().getStaticQueryOptions().toBuilder()
            .context(contexts).build();
        final List<Node> townyNodes = townyNodes(uuid, world);
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user ->
        {
            acquire(user, townyNodes);
            try
            {
                final CachedPermissionData data = user.getCachedData().getPermissionData(options);
                return new OfflinePlayerPermissions.Scope(node ->
                {
                    final Tristate result = data.checkPermission(node);
                    return result == Tristate.UNDEFINED ? null : result == Tristate.TRUE;
                }, () -> release(user, townyNodes));
            }
            catch (RuntimeException exception)
            {
                release(user, townyNodes);
                throw exception;
            }
        });
    }

    private static List<Node> townyNodes(UUID uuid, String world)
    {
        if (!Bukkit.getPluginManager().isPluginEnabled("Towny")) return Collections.emptyList();
        final TownyWorld townyWorld = TownyAPI.getInstance().getTownyWorld(world);
        if (townyWorld == null || !townyWorld.isUsingTowny()) return Collections.emptyList();
        final Resident resident = TownyAPI.getInstance().getResident(uuid);
        if (resident == null) return Collections.emptyList();
        final List<Node> nodes = new ArrayList<>();
        for (Map.Entry<String, Boolean> permission : TownyPerms.getResidentPerms(resident).entrySet())
        {
            final String node = permission.getKey();
            // Only protection overrides are needed here; do not grant unrelated Towny commands.
            if (node.equals("*") || node.equals("towny.*") || node.equals("towny.admin") ||
                node.startsWith("towny.wild.") || node.startsWith("towny.claimed.") || node.startsWith("towny.admin."))
                nodes.add(PermissionNode.builder(node).value(permission.getValue()).withContext("world", world).build());
        }
        return nodes;
    }

    static synchronized void acquire(User user, List<Node> nodes)
    {
        final Map<Node, Grant> grants = GRANTS.computeIfAbsent(user, ignored -> new HashMap<>());
        for (Node node : nodes)
        {
            final Grant grant = grants.get(node);
            if (grant != null) ++grant.users;
            else grants.put(node, new Grant(user.transientData().add(node) == DataMutateResult.SUCCESS));
        }
    }

    static synchronized void release(User user, List<Node> nodes)
    {
        final Map<Node, Grant> grants = GRANTS.get(user);
        if (grants == null) return;
        for (Node node : nodes)
        {
            final Grant grant = grants.get(node);
            if (grant == null || --grant.users > 0) continue;
            if (grant.added) user.transientData().remove(node);
            grants.remove(node);
        }
        if (grants.isEmpty()) GRANTS.remove(user);
    }
}
