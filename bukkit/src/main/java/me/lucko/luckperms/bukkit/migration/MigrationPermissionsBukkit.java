/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.bukkit.migration;

import com.platymuus.bukkit.permissions.PermissionsPlugin;

import me.lucko.luckperms.common.command.CommandResult;
import me.lucko.luckperms.common.command.abstraction.ChildCommand;
import me.lucko.luckperms.common.command.access.CommandPermission;
import me.lucko.luckperms.common.command.spec.CommandSpec;
import me.lucko.luckperms.common.command.utils.ArgumentList;
import me.lucko.luckperms.common.commands.migration.MigrationUtils;
import me.lucko.luckperms.common.locale.Message;
import me.lucko.luckperms.common.model.Group;
import me.lucko.luckperms.common.model.PermissionHolder;
import me.lucko.luckperms.common.model.User;
import me.lucko.luckperms.common.node.types.Inheritance;
import me.lucko.luckperms.common.plugin.LuckPermsPlugin;
import me.lucko.luckperms.common.sender.Sender;
import me.lucko.luckperms.common.util.Iterators;
import me.lucko.luckperms.common.util.Predicates;
import me.lucko.luckperms.common.util.ProgressLogger;

import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.event.cause.CreationCause;
import net.luckperms.api.model.data.DataType;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class MigrationPermissionsBukkit extends ChildCommand<Object> {
    public MigrationPermissionsBukkit() {
        super(CommandSpec.MIGRATION_COMMAND, "permissionsbukkit", CommandPermission.MIGRATION, Predicates.alwaysFalse());
    }

    @Override
    public CommandResult execute(LuckPermsPlugin plugin, Sender sender, Object ignored, ArgumentList args, String label) {
        ProgressLogger log = new ProgressLogger(Message.MIGRATION_LOG, Message.MIGRATION_LOG_PROGRESS, "PermissionsBukkit");
        log.addListener(plugin.getConsoleSender());
        log.addListener(sender);

        log.log("Starting.");
        
        if (!Bukkit.getPluginManager().isPluginEnabled("PermissionsBukkit")) {
            log.logError("Plugin not loaded.");
            return CommandResult.STATE_ERROR;
        }

        PermissionsPlugin permissionsBukkit = (PermissionsPlugin) Bukkit.getPluginManager().getPlugin("PermissionsBukkit");
        FileConfiguration config = permissionsBukkit.getConfig();

        // Migrate all groups
        log.log("Starting group migration.");
        AtomicInteger groupCount = new AtomicInteger(0);

        ConfigurationSection groupsSection = config.getConfigurationSection("groups");

        Iterators.tryIterate(groupsSection.getKeys(false), key -> {
            final String groupName = MigrationUtils.standardizeName(key);
            Group lpGroup = plugin.getStorage().createAndLoadGroup(groupName, CreationCause.INTERNAL).join();

            // migrate data
            if (groupsSection.isConfigurationSection(key)) {
                migrate(lpGroup, groupsSection.getConfigurationSection(key));
            }

            plugin.getStorage().saveGroup(lpGroup).join();
            log.logAllProgress("Migrated {} groups so far.", groupCount.incrementAndGet());
        });
        log.log("Migrated " + groupCount.get() + " groups");

        // Migrate all users
        log.log("Starting user migration.");
        AtomicInteger userCount = new AtomicInteger(0);

        ConfigurationSection usersSection = config.getConfigurationSection("users");

        Iterators.tryIterate(usersSection.getKeys(false), key -> {
            UUID uuid = BukkitUuids.lookupUuid(log, key);
            if (uuid == null) {
                return;
            }

            User lpUser = plugin.getStorage().loadUser(uuid, null).join();

            // migrate data
            if (usersSection.isConfigurationSection(key)) {
                migrate(lpUser, usersSection.getConfigurationSection(key));
            }

            plugin.getUserManager().getHouseKeeper().cleanup(lpUser.getUniqueId());
            plugin.getStorage().saveUser(lpUser);
            log.logProgress("Migrated {} users so far.", userCount.incrementAndGet(), ProgressLogger.DEFAULT_NOTIFY_FREQUENCY);
        });

        log.log("Migrated " + userCount.get() + " users.");
        log.log("Success! Migration complete.");
        log.log("Don't forget to remove the PermissionsBukkit jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
        return CommandResult.SUCCESS;
    }

    private static void migrate(PermissionHolder holder, ConfigurationSection data) {
        // migrate permissions
        if (data.isConfigurationSection("permissions")) {
            ConfigurationSection permsSection = data.getConfigurationSection("permissions");
            for (String perm : permsSection.getKeys(false)) {
                boolean value = permsSection.getBoolean(perm);
                holder.setNode(DataType.NORMAL, MigrationUtils.parseNode(perm, value).build(), true);
            }
        }

        if (data.isConfigurationSection("worlds")) {
            ConfigurationSection worldSection = data.getConfigurationSection("worlds");
            for (String world : worldSection.getKeys(false)) {
                if (worldSection.isConfigurationSection(world)) {
                    ConfigurationSection permsSection = worldSection.getConfigurationSection(world);
                    for (String perm : permsSection.getKeys(false)) {
                        boolean value = permsSection.getBoolean(perm);
                        holder.setNode(DataType.NORMAL, MigrationUtils.parseNode(perm, value).withContext(DefaultContextKeys.WORLD_KEY, world).build(), true);
                    }
                }
            }
        }

        // migrate parents
        if (data.isList("groups")) {
            List<String> groups = data.getStringList("groups");
            for (String group : groups) {
                holder.setNode(DataType.NORMAL, Inheritance.builder(MigrationUtils.standardizeName(group)).build(), true);
            }
        }
        if (data.isList("inheritance")) {
            List<String> groups = data.getStringList("inheritance");
            for (String group : groups) {
                holder.setNode(DataType.NORMAL, Inheritance.builder(MigrationUtils.standardizeName(group)).build(), true);
            }
        }
    }

}
