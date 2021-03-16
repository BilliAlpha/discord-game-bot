package com.billialpha.discord.gamebot.games;

import java.util.Objects;

/**
 * Specs for building a game
 */
public class GameSpec<T extends Game> {
    private final GameRegistry.GameType<T> gameType;

    public static <T extends Game> GameSpec<T> of(GameRegistry.GameType<T> gameType) {
        Objects.requireNonNull(gameType, "Game type cannot be null");
        return new GameSpec<>(gameType);
    }

    public static GameSpec<?> of(String gameType) {
        return of(GameRegistry.get().get(gameType));
    }

    private GameSpec(GameRegistry.GameType<T> gameType) {
        this.gameType = gameType;
    }

    public GameRegistry.GameType<T> getType() {
        return gameType;
    }

    public T build(GameInstance instance) {
        return gameType.newInstance(instance);
    }
}
