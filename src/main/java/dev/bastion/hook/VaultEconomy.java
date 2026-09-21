package dev.bastion.hook;

import dev.bastion.dungeon.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;
import java.util.Locale;

/** Vault's economy, reached by reflection so the plugin has no compile-time dependency on it. */
public final class VaultEconomy implements Rewards.Economy {

    private final Object provider;
    private final Method has, withdraw, deposit, format, success;

    private VaultEconomy(Object provider, Class<?> economy, Class<?> response) throws ReflectiveOperationException {
        this.provider = provider;
        this.has = economy.getMethod("has", OfflinePlayer.class, double.class);
        this.withdraw = economy.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
        this.deposit = economy.getMethod("depositPlayer", OfflinePlayer.class, double.class);
        this.format = economy.getMethod("format", double.class);
        this.success = response.getMethod("transactionSuccess");
    }

    /** Null if Vault or an economy plugin is missing. */
    public static VaultEconomy hook() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null;
        try {
            Class<?> economy = Class.forName("net.milkbowl.vault.economy.Economy");
            Class<?> response = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(economy);
            return registration == null ? null : new VaultEconomy(registration.getProvider(), economy, response);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        try {
            return (boolean) has.invoke(provider, player, amount);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @Override
    public boolean withdraw(OfflinePlayer player, double amount) {
        try {
            return (boolean) success.invoke(withdraw.invoke(provider, player, amount));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    @Override
    public void deposit(OfflinePlayer player, double amount) {
        try {
            deposit.invoke(provider, player, amount);
        } catch (ReflectiveOperationException ignored) {
            // nothing to do: the money stays where it was
        }
    }

    @Override
    public String format(double amount) {
        try {
            return (String) format.invoke(provider, amount);
        } catch (ReflectiveOperationException e) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    }
}
