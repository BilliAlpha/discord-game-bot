package com.billialpha.discord.gamebot.games.quizz;

import com.billialpha.discord.gamebot.games.Game;
import com.billialpha.discord.gamebot.games.GameInstance;
import discord4j.common.util.Snowflake;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.event.domain.message.ReactionAddEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Category;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.reaction.ReactionEmoji;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created on 11/03/2021.
 *
 * @author <a href="mailto:billi.pamege.300@gmail.com">BilliAlpha</a>
 */
public class QuizzGame implements Game {
    public final GameInstance game;
    private final List<QuizzRound> rounds;
    private Tuple2<Snowflake, Snowflake> messageId;
    private Snowflake hostPlayer;

    public QuizzGame(GameInstance inst) {
        this.game = inst;
        rounds = new ArrayList<>();
    }

    @Override
    public Mono<Void> onStart(GuildMessageChannel chan, Member player) {
        hostPlayer = player.getId();
        game.registerPlayer(hostPlayer);
        return chan.createMessage("**On démarre un quizz !**\n" +
                "Réagissez à ce message pour participer.\n\n" +
                "Quand l'hôte réagis la partie commence.")
                .doOnSuccess(msg -> messageId = Tuples.of(chan.getId(), msg.getId()))
                .flatMap(m -> m.addReaction(ReactionEmoji.unicode("✅"))).then();
    }

    @Override
    public Mono<Void> onReaction(ReactionAddEvent evt) {
        if (evt.getGuildId().isEmpty()) return Mono.empty();
        if (!evt.getChannelId().equals(messageId.getT1())) return Mono.empty(); // Wrong channel
        boolean isHost = evt.getUserId().equals(hostPlayer);
        if (evt.getMessageId().equals(messageId.getT2())) {
            if (game.getState() == GameInstance.State.STARTING) {
                game.registerPlayer(evt.getUserId());
                if (isHost) {
                    if (getActualPlayers().size() < 1) {
                        game.setInactive();
                        return evt.getMessage()
                                .flatMap(m -> m.edit(e -> e.setContent("Il n'y a pas assez de joueurs !")))
                                .flatMap(Message::removeAllReactions);
                    }
                    return launchGame();
                }
                return Mono.empty();
            }
            return Mono.empty();
        }
        if (!isHost) return Mono.empty(); // Not the host
        List<QuizzRound> reversedRounds = new ArrayList<>(rounds.size());
        reversedRounds.addAll(rounds);
        Collections.reverse(reversedRounds);
        return Flux.fromIterable(reversedRounds)
                .filter(r -> r.getMessageId().equals(evt.getMessageId()))
                .flatMap(QuizzRound::stop)
                .then();
    }

    @Override
    public Mono<Void> onDirectMessage(MessageCreateEvent evt) {
        User p = evt.getMessage().getAuthor().orElse(null);
        Objects.requireNonNull(p, "Message sender cannot be null");
        if (p.getId().equals(hostPlayer)) {
            String[] msg = evt.getMessage().getContent().split("\n", 2);
            if (msg[0].equals("stop")) {
                game.setInactive();
                return game.client.getChannelById(messageId.getT1())
                        .ofType(GuildMessageChannel.class)
                        .flatMap(chan -> chan.createMessage("**Le quizz est terminé**"))
                        .then();
            }
            String desc = msg.length > 1 && msg[1].length() > 0 ? msg[1] : null;
            QuizzRound round = new QuizzRound(this, messageId.getT1(), msg[0], desc);
            rounds.add(round);
            return round.start(evt.getMessage());
        }
        if (rounds.size()<1) return Mono.empty(); // No Round
        QuizzRound round = rounds.get(rounds.size()-1);
        if (!round.isRunning()) return Mono.empty();
        return round.onAnswer(p, evt.getMessage().getContent())
                .flatMap(reply -> evt.getMessage().getChannel()
                        .flatMap(chan -> chan.createMessage(x -> {
                            x.setMessageReference(evt.getMessage().getId());
                            x.setContent(reply);
                        })))
                .then();
    }

    @Override
    public Mono<Void> onGuildMessage(MessageCreateEvent evt) {
        User p = evt.getMessage().getAuthor().orElse(null);
        Objects.requireNonNull(p, "Message sender cannot be null");
        if (p.getId().equals(hostPlayer)) return Mono.empty();
        String msg = evt.getMessage().getContent();
        String selfMentionRegex = "^<@!?"+ game.client.getSelfId().asLong()+">";
        if (!msg.matches(selfMentionRegex+".*")) return Mono.empty(); // Not mention
        String answer = msg.replaceFirst(selfMentionRegex, "").trim();
        if (rounds.size()<1) return Mono.empty(); // No round
        QuizzRound round = rounds.get(rounds.size()-1);
        if (!round.isRunning()) return Mono.empty();
        return round.onAnswer(p, answer)
                .flatMap(reply -> evt.getMessage().getChannel()
                        .flatMap(chan -> chan.createMessage(x -> {
                            x.setMessageReference(evt.getMessage().getId());
                            x.setContent(reply);
                        })))
                .then();
    }

    public Set<Snowflake> getActualPlayers() {
        return game.getPlayers().stream().filter(id -> !id.equals(hostPlayer)).collect(Collectors.toSet());
    }

    public Flux<GuildMessageChannel> getAnswerChannels() {
        String catName = "question pour un chalet"; // FIXME: Hardcoded category name
        return game.client.getGuildChannels(game.getGuildId())
                .ofType(Category.class)
                .filter(c -> c.getName().equalsIgnoreCase(catName))
                .single()
                .flatMapMany(Category::getChannels)
                .ofType(GuildMessageChannel.class)
                .filter(c -> !c.getId().equals(messageId.getT1()));
    }

    private Mono<Void> launchGame() {
        return Mono.fromRunnable(game::setActive)
                // Update game message
                .thenMany(Flux.fromIterable(getActualPlayers()))
                .flatMap(game.client::getUserById)
                .map(User::getMention)
                .collect(Collectors.joining("\n • "))
                .map(playerList -> "**Le quizz à démarré**\nParticipants:\n • "+playerList)
                .flatMap(message -> game.client.getMessageById(messageId.getT1(), messageId.getT2())
                        .flatMap(msg -> msg.edit(m -> m.setContent(message))))
                .flatMap(Message::removeAllReactions)
                // Send message to host
                .thenReturn(hostPlayer)
                .flatMap(game.client::getUserById)
                .flatMap(User::getPrivateChannel)
                .flatMap(c -> c.createMessage("Dès que vous êtes prêt, envoyez la question.\n" +
                        "Envoyez `stop` pour terminer le quizz."))
                // Send message in player channels
                .thenMany(getAnswerChannels())
                .flatMap(chan -> chan.createMessage("*La partie vient de commencer, " +
                        "vous allez recevoir les questions ici.*"))
                .then();
    }
}
