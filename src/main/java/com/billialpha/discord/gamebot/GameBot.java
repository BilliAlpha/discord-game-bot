package com.billialpha.discord.gamebot;

import com.billialpha.discord.gamebot.games.GameInstance;
import com.billialpha.discord.gamebot.games.GameRegistry;
import com.billialpha.discord.gamebot.games.GameSpec;
import com.billialpha.discord.gamebot.games.quizz.QuizzGame;
import com.billialpha.discord.gamebot.games.quizz.QuizzRound;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.DiscordClientBuilder;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.presence.Presence;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.rest.util.Permission;
import discord4j.rest.util.PermissionSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.function.TupleUtils;

import java.util.HashSet;
import java.util.Set;

/**
 * Main class
 */
public class GameBot {
    private static GameBot instance;
    public static final String VERSION = "0.1.0";
    public static final Logger LOG = LoggerFactory.getLogger(GameBot.class);

    public static GameBot get() {
        return instance;
    }

    public final GatewayDiscordClient client;
    private Set<GameInstance> games;

    private GameBot(GatewayDiscordClient client) {
        instance = this;
        this.client = client;
        this.games = new HashSet<>();
    }

    public Mono<Void> start() {
        this.client.on(MessageCreateEvent.class, this::onMessage).subscribe();
        this.client.on(ReactionAddEvent.class, this::onReaction).subscribe();
        return this.client.updatePresence(Presence.online());
    }

    private Mono<Void> onMessage(MessageCreateEvent evt) {
        if (evt.getMessage().getAuthor().isEmpty()) return Mono.empty(); // Ignore system messages
        User author = evt.getMessage().getAuthor().get();
        if (author.getId().equals(client.getSelfId())) return Mono.empty(); // Ignore self messages
        String msg = evt.getMessage().getContent();
        if (evt.getGuildId().isEmpty()) { // PM
            LOG.debug("Received direct message: "+msg);
            if (msg.equals("quit") && author.getId().asLong() == 149882468571283457L) {
                LOG.info("Got quit message: "+author.getUsername());
                return this.client.logout();
            }
            return Flux.fromIterable(games)
                    .filter(g -> g.getState() == GameInstance.State.ACTIVE)
                    .flatMap(g -> g.onDirectMessage(evt))
                    .then();
        }
        Snowflake guildId = evt.getGuildId().get();
        if (msg.startsWith("%start ")) {
            LOG.info("Got start action: "+author.getUsername()+"/g:"+guildId.asLong()+" >> "+msg);
            if (evt.getMember().isEmpty()) return Mono.empty();
            return //* // Security: Check host has Manage Guild permission
                    evt.getMember().get().getBasePermissions()
                    .map(perms -> perms.contains(Permission.MANAGE_GUILD))
                    .filter(Boolean::booleanValue)
                    .switchIfEmpty(evt.getMessage().getChannel()
                            .flatMap(chan -> chan.createMessage(s -> s.setContent("Nope!")))
                            .thenReturn(false))
                    .filter(Boolean::booleanValue)
                    //*/ Mono.just(0)
                    .flatMap(x -> {
                        // Build game instance
                        String typeName = msg.substring(7);
                        GameSpec<?> specs;
                        try {
                            specs = GameSpec.of(typeName);
                        } catch (NullPointerException ex) {
                            return evt.getMessage().addReaction(ReactionEmoji.unicode("❓"));
                        }
                        GameInstance game = new GameInstance(client, guildId, specs);
                        games.add(game);
                        // Start game instance
                        return evt.getMessage().getChannel()
                                .ofType(GuildMessageChannel.class)
                                .zipWith(Mono.justOrEmpty(evt.getMember()))
                                .flatMap(TupleUtils.function(game::start));
                    });
        }
        // Dispatch messages to active games
        return Flux.fromIterable(games)
                .filter(g -> g.getState() == GameInstance.State.ACTIVE)
                .filter(g -> g.getGuildId().equals(guildId))
                .flatMap(g -> g.onGuildMessage(evt))
                .then();
    }

    private Mono<Void> onReaction(ReactionAddEvent evt) {
        if (evt.getUserId().equals(client.getSelfId())) return Mono.empty(); // Ignore self reactions
        return Flux.fromIterable(games)
                .filter(g -> g.getState() != GameInstance.State.INACTIVE)
                .filter(g -> g.getState() == GameInstance.State.STARTING || g.getPlayers().contains(evt.getUserId()))
                .flatMap(g -> g.onReaction(evt))
                .then();
    }

    public static void main(String[] args) {
        LOG.info("Starting GameBot (v"+VERSION+")");

        // Retrieve bot token
        String discordBotToken = System.getenv("DISCORD_TOKEN");
        if (discordBotToken == null) {
            LOG.error("Couldn't find discord bot token");
            System.exit(1);
        }

        // Register game types
        GameRegistry.get().register("quizz", QuizzGame::new);

        // Build discord client
        DiscordClient initClient = DiscordClientBuilder.create(discordBotToken).build();
        GatewayDiscordClient gwClient = initClient.login().block();

        // Start game bot
        GameBot bot = new GameBot(gwClient);
        bot.start().then(bot.client.onDisconnect()).block();
    }
}
