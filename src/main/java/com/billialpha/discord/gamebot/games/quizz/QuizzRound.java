package com.billialpha.discord.gamebot.games.quizz;

import com.billialpha.discord.gamebot.games.GameInstance;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.GuildMessageChannel;
import discord4j.core.object.reaction.ReactionEmoji;
import discord4j.core.spec.EmbedCreateSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single game round
 */
public class QuizzRound {
    public static final Logger LOG = LoggerFactory.getLogger(QuizzRound.class);
    private final QuizzGame quizz;
    public final Snowflake channelId;
    public final String title;
    public final String desc;
    private Instant startTime;
    private Snowflake messageId;
    private final Map<Snowflake, Answer> answers;
    private boolean running;

    public QuizzRound(QuizzGame quizz, Snowflake channelId, String title, String desc) {
        this.quizz = quizz;
        this.channelId = channelId;
        this.title = title;
        this.desc = desc;
        this.answers = new HashMap<>();
        this.running = false;
    }

    public Snowflake getMessageId() {
        return messageId;
    }

    public boolean isRunning() {
        return running;
    }

    public Flux<Answer> getAnswers() {
        return Flux.fromIterable(answers.values())
                .sort(Comparator.comparing(a -> a.time));
    }

    public Optional<Answer> getAnswer(Snowflake player) {
        return Optional.ofNullable(answers.get(player));
    }

    public Mono<Void> start(Message source) {
        if (running) return Mono.error(new IllegalStateException("Round already running"));
        this.running = true;
        this.startTime = Instant.now();
        LOG.info("Starting round: "+title);
        // Timeout 60s
        Mono.just(1).delayElement(Duration.ofSeconds(60)) // FIXME: Hardcoded timeout
                .flatMap(x -> stop())
                .subscribe();

        return Mono.when(
                // Acknowledge host question
                source.addReaction(ReactionEmoji.unicode("✅")),

                // Create guild message
                quizz.game.client.getChannelById(channelId)
                        .ofType(GuildMessageChannel.class)
                        .flatMap(chan -> chan.createEmbed(x -> createEmbed(x, title, desc, null, running)))
                        .doOnSuccess(m -> messageId = m.getId())
                        .flatMap(m -> m.addReaction(ReactionEmoji.unicode("\uD83D\uDCBE"))), // Icon: Floppy disk

                // Send question in channels
                quizz.getAnswerChannels()
                        .parallel()
                        .flatMap(chan -> chan.createMessage("> **"+title+"**"+(desc != null ? "\n"+desc : "")))
        );
    }

    public Mono<String> onAnswer(User player, String msg) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(msg);
        if (!running) return Mono.error(new IllegalStateException("Round not running"));
        int order;
        Answer answer;
        synchronized (answers) {
            if (answers.containsKey(player.getId())) return Mono.empty();
            LOG.info("Registering answer: "+player.getUsername()+" >> "+msg);
            answer = new Answer(player.getId(), Instant.now(), msg);
            answers.put(player.getId(), answer);
            order = answers.size();
        }

        // Default: update guild message
        Mono<Void> mono = quizz.game.client.getMessageById(channelId, messageId)
                .flatMap(this::editMessage).then();

        // All players answered: stop
        if (order == quizz.getActualPlayers().size())
            mono = stop();

        // Return reply: tell order to player
        return mono.thenReturn("Vous êtes "+order+(order == 1 ? "er" : "ème")+
                " ("+answer.getResponseTime(startTime)+"s)");
    }

    public Mono<Void> stop() {
        if (!running) return Mono.empty();
        LOG.info("Ending round");
        this.running = false;
        // Update guild message
        return quizz.game.client.getMessageById(channelId, messageId)
                .flatMap(this::editMessage)
                .flatMap(Message::removeAllReactions)
                .then();
    }

    private Mono<Message> editMessage(Message m) {
        return getAnswers()
                .flatMap(ans -> ans.describe(quizz.game, startTime, !running))
                .collectList()
                .flatMap(ans -> m.edit(e -> e.setEmbed(x -> createEmbed(x, title, desc, ans, running))));
    }

    public static void createEmbed(EmbedCreateSpec spec, String title, String desc,
                                   List<String> answers, boolean running) {
        spec.setTitle(title);
        if (desc != null) spec.setDescription(desc);
        StringBuilder msg = new StringBuilder();
        if (answers != null && answers.size() > 0) {
            for (int i = 0; i < answers.size(); i++) {
                msg.append(i+1).append(") ").append(answers.get(i)).append("\n");
            }
            if (running) msg.append("...");
        } else if (!running) {
            msg.append("*Aucune réponse.*");
        } else {
            msg.append("*En attente des réponses...*");
        }
        spec.addField("Réponses", msg.toString(), false);
    }

    public static class Answer {
        public final Snowflake userId;
        public final Instant time;
        public final String answer;

        public Answer(Snowflake userId, Instant time, String answer) {
            this.userId = Objects.requireNonNull(userId);
            this.time = Objects.requireNonNull(time);
            this.answer = Objects.requireNonNull(answer);
        }

        public Snowflake getUserId() {
            return userId;
        }

        public Instant getTime() {
            return time;
        }

        public String getResponseTime(Instant startTime) {
            return String.format("%.2f", startTime.until(time, ChronoUnit.MILLIS)/1000f);
        }

        public String getAnswer() {
            return answer;
        }

        public Mono<String> describe(GameInstance game, Instant startTime, boolean withAnswer) {
            Mono<String> mono = game.client.getMemberById(game.getGuildId(), userId)
                    .map(Member::getNicknameMention)
                    .map(s -> s+" ("+getResponseTime(startTime)+"s)");
            return withAnswer ? mono.map(s -> s+": `"+answer+"`") : mono;
        }
    }
}
