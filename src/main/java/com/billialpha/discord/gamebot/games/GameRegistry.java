package com.billialpha.discord.gamebot.games;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A registry of all game types
 */
public class GameRegistry {
    public static final GameRegistry instance = new GameRegistry();
    public static GameRegistry get() {
        return instance;
    }

    private final Map<String, GameType<?>> types;

    private GameRegistry() {
        this.types = new HashMap<>();
    }

    // --- Getters ---

    public GameType<?> get(String type) {
        return types.get(type);
    }

    // --- Modifiers ---

    public void clear() {
        types.clear();
    }

    public void register(GameType<?> type) {
        if (types.containsKey(type.name)) throw new IllegalArgumentException("Type name already used");
        types.put(type.name, type);
    }

    public void register(String name, Function<GameInstance, Game> instanceBuilder) {
        register(new GameType<>(name, instanceBuilder));
    }

    // --- Subclasses ---

    /**
     * A single game type
     * @param <T> The type of game
     */
    public static class GameType<T extends Game> {
        public final String name;
        private final Function<GameInstance, T> instanceBuilder;

        public GameType(String name, Function<GameInstance, T> instanceBuilder) {
            this.name = name;
            this.instanceBuilder = instanceBuilder;
        }

        public String getName() {
            return name;
        }

        public T newInstance(GameInstance instance) {
            return instanceBuilder.apply(instance);
        }
    }
}
