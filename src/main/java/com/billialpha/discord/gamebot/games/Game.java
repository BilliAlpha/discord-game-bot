package com.billialpha.discord.gamebot.games;

import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import reactor.core.publisher.Mono;

/**
 * The interface to be implemented by all games
 */
public interface Game {
    default Mono<Void> onStart(GuildMessageChannel chan, Member player) {
        return Mono.empty();
    }

    default Mono<Void> onGuildMessage(MessageCreateEvent evt) {
        return Mono.empty();
    }

    default Mono<Void> onDirectMessage(MessageCreateEvent evt) {
        return Mono.empty();
    }

    default Mono<Void> onReaction(ReactionAddEvent evt) {
        return Mono.empty();
    }
}
