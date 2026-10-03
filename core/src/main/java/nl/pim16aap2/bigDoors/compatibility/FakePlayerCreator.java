package nl.pim16aap2.bigDoors.compatibility;

import nl.pim16aap2.bigDoors.BigDoors;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import org.jetbrains.annotations.Nullable;
import java.util.function.BiFunction;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Class used to create a fake-online player who is actually offline.
 *
 * @author Pim
 */
public class FakePlayerCreator
{
    public static final String FAKE_PLAYER_METADATA = "isBigDoorsFakePlayer";

    private final BigDoors plugin;
    private final @Nullable BiFunction<OfflinePlayer, Location, Player> fakePlayerInstantiator;

    public FakePlayerCreator(final BigDoors plugin)
    {
        this.plugin = plugin;
        fakePlayerInstantiator = createFakePlayerInstantiator(plugin);
        if (fakePlayerInstantiator != null)
            CraftPlayerPermissionShell.prepare();
    }

    @Nullable Player getFakePlayer(OfflinePlayer oPlayer, Location location)
    {
        return fakePlayerInstantiator == null ? null : fakePlayerInstantiator.apply(oPlayer, location);
    }

    CompletableFuture<@Nullable Player> getFakePlayerAsync(OfflinePlayer player, Location location)
    {
        final Player fakePlayer = getFakePlayer(player, location);
        if (fakePlayer == null)
            return CompletableFuture.completedFuture(null);
        return OfflinePlayerPermissions.prepare(plugin, player, location)
            .thenApply(permissions -> OfflinePlayerPermissions.wrap(fakePlayer, permissions));
    }

    private @Nullable BiFunction<OfflinePlayer, Location, Player> createFakePlayerInstantiator(BigDoors plugin)
    {
        try
        {
            return new FakePlayerClassGenerator(plugin).getInstantiator();

        }
        catch (Exception exception)
        {
            BigDoors.get().getMyLogger().log(
                "Failed to create fake player constructor! Checks for offline players will not work!",
                exception
            );
            return null;
        }
        catch (NoClassDefFoundError e)
        {
            boolean isByteBuddy = e.getMessage().startsWith("net/bytebuddy");
            if (isByteBuddy)
            {
                BigDoors.get().getMyLogger().logMessage(
                    Level.SEVERE,
                    "Could not find ByteBuddy classes. Either the Library Loader failed or you are on a legacy version that does not have it (<1.16.5). Checks for offline players will not work!"
                );
                return null;
            }

            BigDoors.get().getMyLogger().logMessage(
                Level.SEVERE,
                "Could not find class '" + e.getMessage() + "'. Checks for offline players will not work!"
            );
            return null;
        }
    }
}
