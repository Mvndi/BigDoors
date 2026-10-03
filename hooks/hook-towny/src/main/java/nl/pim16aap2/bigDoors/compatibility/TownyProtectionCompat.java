package nl.pim16aap2.bigDoors.compatibility;

import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.Towny;
import org.bukkit.Bukkit;
import com.palmergames.bukkit.towny.object.TownyPermission.ActionType;
import com.palmergames.bukkit.towny.utils.PlayerCacheUtil;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Compatibility hook for the new version of PlotSquared.
 *
 * @see IProtectionCompat
 * @author Pim
 */
public class TownyProtectionCompat implements IProtectionCompat
{
    private final boolean success;
    private final HookContext hookContext;

    public TownyProtectionCompat(HookContext hookContext)
    {
        this.hookContext = hookContext;
        TownyAPI townyAPI = TownyAPI.getInstance();
        success = townyAPI != null;
    }

    @Override
    public boolean canBreakBlock(final Player player, final Location loc)
    {
        final boolean offline = Bukkit.getPlayer(player.getUniqueId()) == null;
        if (offline) Towny.getPlugin().deleteCache(player);
        try
        {
            return canBreakBlockWithCache(player, loc);
        }
        finally
        {
            if (offline) Towny.getPlugin().deleteCache(player);
        }
    }

    private boolean canBreakBlockWithCache(final Player player, final Location location)
    {
        return PlayerCacheUtil.getCachePermission(player, location, location.getBlock().getType(), ActionType.DESTROY);
    }

    @Override
    public boolean canBreakBlocksBetweenLocs(final Player player, final Location loc1, final Location loc2)
    {
        int x1 = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int y1 = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int z1 = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int x2 = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int y2 = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int z2 = Math.max(loc1.getBlockZ(), loc2.getBlockZ());

        final boolean offline = Bukkit.getPlayer(player.getUniqueId()) == null;
        // An online owner's cached overrides must not authorize a later fake-player check, or vice versa.
        // Reset once per cuboid, retaining Towny's per-material/townblock cache within this check.
        if (offline) Towny.getPlugin().deleteCache(player);
        try
        {
            for (int xPos = x1; xPos <= x2; ++xPos)
                for (int yPos = y1; yPos <= y2; ++yPos)
                    for (int zPos = z1; zPos <= z2; ++zPos)
                        if (!canBreakBlockWithCache(player, new Location(loc1.getWorld(), xPos, yPos, zPos)))
                            return false;
            return true;
        }
        finally
        {
            if (offline) Towny.getPlugin().deleteCache(player);
        }
    }

    @Override
    public boolean success()
    {
        return success;
    }

    @Override
    public String getName()
    {
        return hookContext.getProtectionCompatDefinition().getName();
    }
}


