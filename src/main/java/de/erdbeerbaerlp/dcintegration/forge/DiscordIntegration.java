package de.erdbeerbaerlp.dcintegration.forge;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.erdbeerbaerlp.dcintegration.common.Discord;
import de.erdbeerbaerlp.dcintegration.common.compat.DynmapListener;
import de.erdbeerbaerlp.dcintegration.common.discordCommands.CommandRegistry;
import de.erdbeerbaerlp.dcintegration.common.storage.Configuration;
import de.erdbeerbaerlp.dcintegration.common.storage.PlayerLinkController;
import de.erdbeerbaerlp.dcintegration.common.util.DiscordMessage;
import de.erdbeerbaerlp.dcintegration.common.util.MessageUtils;
import de.erdbeerbaerlp.dcintegration.common.util.UpdateChecker;
import de.erdbeerbaerlp.dcintegration.common.util.Variables;
import de.erdbeerbaerlp.dcintegration.forge.api.ForgeDiscordEventHandler;
import de.erdbeerbaerlp.dcintegration.forge.command.McCommandDiscord;
import de.erdbeerbaerlp.dcintegration.forge.mixin.MixinNetHandlerPlayServer;
import de.erdbeerbaerlp.dcintegration.forge.util.ForgeMessageUtils;
import de.erdbeerbaerlp.dcintegration.forge.util.ForgeServerInterface;
import net.dv8tion.jda.api.entities.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.server.FMLServerStartedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppedEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static de.erdbeerbaerlp.dcintegration.common.util.Variables.discordDataDir;
import static de.erdbeerbaerlp.dcintegration.common.util.Variables.discord_instance;

@Mod(DiscordIntegration.MODID)
public class DiscordIntegration {
    /**
     * Modid
     */
    public static final String MODID = "dcintegration";
    /**
     * Contains timed-out player UUIDs, gets filled in {@link MixinNetHandlerPlayServer}
     */
    public static final ArrayList<UUID> timeouts = new ArrayList<>();
    private boolean stopped = false;

    public DiscordIntegration() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::serverSetup);

        Configuration.instance().loadConfig();


        MinecraftForge.EVENT_BUS.register(this);
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST,
                () -> Pair.of(() -> FMLNetworkConstants.IGNORESERVERONLY, (a, b) -> true));


        //  ==  Migrate some files from 1.x.x to 2.x.x  ==

        //LinkedPlayers JSON file
        final File linkedOld = new File("./linkedPlayers.json");
        final File linkedNew = new File(discordDataDir, "LinkedPlayers.json");

        //Player Ignores
        final File ignoreOld = new File("./players_ignoring_discord_v2");
        final File ignoreNew = new File(discordDataDir, ".PlayerIgnores");

        //Create data directory if missing
        if (!discordDataDir.exists()) discordDataDir.mkdir();

        //Move Files
        if (linkedOld.exists() && !linkedNew.exists()) {
            linkedOld.renameTo(linkedNew);
        }
        if (ignoreOld.exists() && !ignoreNew.exists()) {
            ignoreOld.renameTo(ignoreNew);
        }
    }


    public void serverSetup(FMLDedicatedServerSetupEvent ev) {
        CommandRegistry.registerDefaultCommandsFromConfig();
        Variables.discord_instance = new Discord(new ForgeServerInterface());
        try {
            //Wait a short time to allow JDA to get initiaized
            System.out.println("Waiting for JDA to initialize to send starting message... (max 5 seconds before skipping)");
            for (int i = 0; i <= 5; i++) {
                if (discord_instance.getJDA() == null) Thread.sleep(1000);
                else break;
            }
            if (discord_instance.getJDA() != null && !Configuration.instance().localization.serverStarting.isEmpty()) {
                Thread.sleep(2000); //Wait for it to cache the channels
                if (discord_instance.getChannel() != null)
                    Variables.startingMsg = discord_instance.sendMessageReturns(Configuration.instance().localization.serverStarting);
            }
        } catch (InterruptedException | NullPointerException ignored) {
        }
    }

    @SubscribeEvent
    public void playerJoin(final PlayerEvent.PlayerLoggedInEvent ev) {
        if(PlayerLinkController.getSettings(null,ev.getPlayer().getUniqueID()).hideFromDiscord)return;
        if (discord_instance != null) {
            discord_instance.sendMessage(Configuration.instance().localization.playerJoin.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));

            // Fix link status (if user does not have role, give the role to the user, or vice versa)
            final Thread fixLinkStatus = new Thread(() -> {
                if (Configuration.instance().linking.linkedRoleID.equals("0")) return;
                final UUID uuid = ev.getPlayer().getUniqueID();
                if (!PlayerLinkController.isPlayerLinked(uuid)) return;
                final Guild guild = discord_instance.getChannel().getGuild();
                final Role linkedRole = guild.getRoleById(Configuration.instance().linking.linkedRoleID);
                if (PlayerLinkController.isPlayerLinked(uuid)) {
                final Member member = guild.getMemberById(PlayerLinkController.getDiscordFromPlayer(uuid));
                    if (!member.getRoles().contains(linkedRole))
                        guild.addRoleToMember(member, linkedRole).queue();
                }
            });
            fixLinkStatus.setDaemon(true);
            fixLinkStatus.start();
        }
    }

    @SubscribeEvent
    public void advancement(AdvancementEvent ev) {
        if(PlayerLinkController.getSettings(null,ev.getPlayer().getUniqueID()).hideFromDiscord)return;
        if (discord_instance != null && ev.getAdvancement() != null && ev.getAdvancement().getDisplay() != null && ev.getAdvancement().getDisplay().shouldAnnounceToChat())
            discord_instance.sendMessage(Configuration.instance().localization.advancementMessage.replace("%player%",
                    TextFormatting.getTextWithoutFormattingCodes(ForgeMessageUtils.formatPlayerName(ev.getPlayer())))
                    .replace("%name%",
                            TextFormatting.getTextWithoutFormattingCodes(ev.getAdvancement()
                                    .getDisplay()
                                    .getTitle()
                                    .getString()))
                    .replace("%desc%",
                            TextFormatting.getTextWithoutFormattingCodes(ev.getAdvancement()
                                    .getDisplay()
                                    .getDescription()
                                    .getString()))
                    .replace("\\n", "\n"));


    }

    @SubscribeEvent
    public void registerCommands(final RegisterCommandsEvent ev) {
        new McCommandDiscord(ev.getDispatcher());
    }

    @SubscribeEvent
    public void serverStarted(final FMLServerStartedEvent ev) {
        System.out.println("Started");
        Variables.started = new Date().getTime();
        if (discord_instance != null)
            if (Variables.startingMsg != null) {
                Variables.startingMsg.thenAccept((a) -> a.editMessage(Configuration.instance().localization.serverStarted).queue());
            } else discord_instance.sendMessage(Configuration.instance().localization.serverStarted);
        if (discord_instance != null) {
            discord_instance.startThreads();
        }
        UpdateChecker.runUpdateCheck();
        if(ModList.get().getModContainerById("dynmap").isPresent()){
            new DynmapListener().register();
        }
    }

    @SubscribeEvent
    public void command(CommandEvent ev) {
        String command = ev.getParseResults().getReader().getString().replaceFirst(Pattern.quote("/"), "");
        if (!Configuration.instance().commandLog.channelID.equals("0")) {
            if (!ArrayUtils.contains(Configuration.instance().commandLog.ignoredCommands, command.split(" ")[0]))
                discord_instance.sendMessage(Configuration.instance().commandLog.message
                        .replace("%sender%", ev.getParseResults().getContext().getLastChild().getSource().getName())
                        .replace("%cmd%", command)
                        .replace("%cmd-no-args%", command.split(" ")[0]), discord_instance.getChannel(Configuration.instance().commandLog.channelID));
        }
        if (discord_instance != null) {
            boolean raw = false;

            if (((command.startsWith("say")) && Configuration.instance().messages.sendOnSayCommand) || (command.startsWith("me") && Configuration.instance().messages.sendOnMeCommand)) {
                String msg = command.replace("say ", "");
                if (command.startsWith("say"))
                    msg = msg.replaceFirst("say ", "");
                if (command.startsWith("me")) {
                    raw = true;
                    msg = "*" + MessageUtils.escapeMarkdown(msg.replaceFirst("me ", "").trim()) + "*";
                }
                try {
                    discord_instance.sendMessage(ev.getParseResults().getContext().getSource().getName(), ev.getParseResults().getContext().getSource().assertIsEntity().getUniqueID().toString(), new DiscordMessage(null, msg, !raw), discord_instance.getChannel(Configuration.instance().advanced.chatOutputChannelID));
                } catch (CommandSyntaxException e) {
                    if (msg.startsWith(Configuration.instance().messages.sayCommandIgnoredPrefix)) return;
                    discord_instance.sendMessage(msg);
                }
            }
        }
    }

    @SubscribeEvent
    public void serverStopping(FMLServerStoppingEvent ev) {
        if (discord_instance != null) {
            discord_instance.sendMessage(Configuration.instance().localization.serverStopped);
            discord_instance.stopThreads();
        }
        this.stopped = true;
    }

    @SubscribeEvent
    public void serverStopped(FMLServerStoppedEvent ev) {
        if (discord_instance != null) {
            if (!stopped && discord_instance.getJDA() != null) ev.getServer().runImmediately(() -> {
                discord_instance.stopThreads();
                try {
                    discord_instance.sendMessageReturns(Configuration.instance().localization.serverCrash).get();
                } catch (InterruptedException | ExecutionException ignored) {
                }
            });
            discord_instance.kill();
        }
    }

    private boolean isModIDBlacklisted(String sender) {
        return ArrayUtils.contains(Configuration.instance().forgeSpecific.IMC_modIdBlacklist, sender);
    }

    //Still untested
    @SubscribeEvent
    public void imc(InterModProcessEvent ev) {
        final Stream<InterModComms.IMCMessage> stream = ev.getIMCStream();
        stream.forEach((msg) -> {
            System.out.println("[IMC-Message] Sender: " + msg.getSenderModId() + " method: " + msg.getMethod());
            if (isModIDBlacklisted(msg.getSenderModId())) return;
            if ((msg.getMethod().equals("Discord-Message") || msg.getMethod().equals("sendMessage"))) {
                discord_instance.sendMessage(msg.getMessageSupplier().get().toString());
            }
        });
    }
    @SubscribeEvent
    public void chat(ServerChatEvent ev) {
        if(PlayerLinkController.getSettings(null,ev.getPlayer().getUniqueID()).hideFromDiscord)return;
        final ITextComponent msg = ev.getComponent();
        if (discord_instance.callEvent((e) -> {
            if (e instanceof ForgeDiscordEventHandler) {
                return ((ForgeDiscordEventHandler) e).onMcChatMessage(ev);
            }
            return false;
        })) return;

        String text = MessageUtils.escapeMarkdown(ev.getMessage().replace("@everyone", "[at]everyone").replace("@here", "[at]here"));
        final MessageEmbed embed = ForgeMessageUtils.genItemStackEmbedIfAvailable(msg);
        if (discord_instance != null) {
            TextChannel channel = discord_instance.getChannel(Configuration.instance().advanced.chatOutputChannelID);
            if(channel == null ) return;
            discord_instance.sendMessage(ForgeMessageUtils.formatPlayerName(ev.getPlayer()), ev.getPlayer().getUniqueID().toString(), new DiscordMessage(embed, text, true), channel);
            final String json = ITextComponent.Serializer.toJson(msg);
            Component comp = GsonComponentSerializer.gson().deserialize(json);
            final String editedJson = GsonComponentSerializer.gson().serialize(MessageUtils.mentionsToNames(comp,channel.getGuild()));
            ev.setComponent(ITextComponent.Serializer.getComponentFromJson(editedJson));
        }

    }

    @SubscribeEvent
    public void death(LivingDeathEvent ev) {
        if(PlayerLinkController.getSettings(null,ev.getEntity().getUniqueID()).hideFromDiscord)return;
        if (ev.getEntity() instanceof PlayerEntity || (ev.getEntity() instanceof TameableEntity && ((TameableEntity) ev.getEntity()).getOwner() instanceof PlayerEntity && Configuration.instance().messages.sendDeathMessagesForTamedAnimals)) {
            if (discord_instance != null) {
                final ITextComponent deathMessage = ev.getSource().getDeathMessage(ev.getEntityLiving());
                final MessageEmbed embed = ForgeMessageUtils.genItemStackEmbedIfAvailable(deathMessage);
                discord_instance.sendMessage(new DiscordMessage(embed, Configuration.instance().localization.playerDeath.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getEntity())).replace("%msg%", TextFormatting.getTextWithoutFormattingCodes(deathMessage.getString()).replace(ev.getEntity().getName().getUnformattedComponentText() + " ", ""))), discord_instance.getChannel(Configuration.instance().advanced.deathsChannelID));
            }
        }
    }

    @SubscribeEvent
    public void playerLeave(PlayerEvent.PlayerLoggedOutEvent ev) {
        if (stopped) return; //Try to fix player leave messages after stop!
        if(PlayerLinkController.getSettings(null,ev.getPlayer().getUniqueID()).hideFromDiscord)return;
        if (discord_instance != null && !timeouts.contains(ev.getPlayer().getUniqueID()))
            discord_instance.sendMessage(Configuration.instance().localization.playerLeave.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));
        else if (discord_instance != null && timeouts.contains(ev.getPlayer().getUniqueID())) {
            discord_instance.sendMessage(Configuration.instance().localization.playerTimeout.replace("%player%", ForgeMessageUtils.formatPlayerName(ev.getPlayer())));
            timeouts.remove(ev.getPlayer().getUniqueID());
        }
    }
}
