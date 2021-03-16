package com.billialpha.discord.gamebot.games;

import discord4j.common.util.Snowflake;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * A single game instance, bound to a guild
 */
public class GameInstance {
    public static final Logger LOG = LoggerFactory.getLogger(GameInstance.class);
    public final GatewayDiscordClient client;
    private final Snowflake guildId;
    private final Set<Snowflake> players;
    private State state;
    private final String gameType;
    private final Game game;

    public GameInstance(GatewayDiscordClient client, Snowflake guildId, GameSpec<?> specs) {
        this.client = client;
        this.guildId = guildId;
        this.players = new HashSet<>();
        this.state = State.INACTIVE;
        this.gameType = specs.getType().name;
        this.game = specs.build(this);
    }

    // --- Getters ---

    public GatewayDiscordClient client() {
        return client;
    }

    public Snowflake getGuildId() {
        return guildId;
    }

    public Set<Snowflake> getPlayers() {
        return Collections.unmodifiableSet(players);
    }

    public State getState() {
        return state;
    }

    // --- Actions ---

    public Mono<Void> start(GuildMessageChannel chan, Member player) {
        LOG.info("Starting "+gameType);
        this.state = State.STARTING;
        return game.onStart(chan, player);
    }

    public void registerPlayer(Snowflake playerId) {
        if (client.getSelfId().equals(playerId)) return;
        if (state == State.ACTIVE) throw new IllegalStateException("Cannot register players when game is active");
        LOG.info("Registering player: "+playerId.asLong());
        players.add(playerId);
    }

    public void setActive() {
        this.state = State.ACTIVE;
    }

    public void setInactive() {
        this.state = State.INACTIVE;
    }

    public Mono<Void> onGuildMessage(MessageCreateEvent evt) {
        return game.onGuildMessage(evt);
    }

    public Mono<Void> onDirectMessage(MessageCreateEvent evt) {
        return game.onDirectMessage(evt);
    }

    public Mono<Void> onReaction(ReactionAddEvent evt) {
        return game.onReaction(evt);
    }

    // --- Subclasses ---

    public enum State {
        INACTIVE,
        STARTING,
        ACTIVE;
    }
}
