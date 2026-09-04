package dev.supergamer2026.paradox;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Plain properties file at config/paradox.properties. Regenerated with defaults if missing. */
public final class ParadoxConfig {

    /** Seconds of history kept, and therefore how long the ghost phase lasts. */
    public static int recordSeconds = 30;
    /** How far out we track entities for the replay. */
    public static double recordRadius = 24.0;
    public static int maxTrackedEntities = 24;
    /** How fast the replay plays back. It is a briefing, not the clock - keep it quick. */
    public static double replaySpeed = 1.25;
    /** Only the last few seconds before the death are worth replaying. */
    public static int replayWindowSeconds = 6;
    /** Seconds you actually get to fix things. Independent of the replay length. */
    public static int interventionSeconds = 60;
    /** How far back the rewind puts you. Longer means you carry on from further before the death. */
    public static int rewindSeconds = 12;
    /** v0.2: rewind the world and let you re-live the seconds your ghost just rescued. */
    public static boolean rewindEnabled = true;
    /** How far around you the block journal watches. Bigger costs more per poll. */
    public static int journalRadius = 12;
    /** Nothing can kill you during the re-live. Off makes it tense but can undo the rescue. */
    public static boolean reliveInvulnerable = true;
    /** Rarely, the re-live leaves something behind that you can see and keep. */
    public static boolean remnantEnabled = true;
    /** Chance per successful re-live that you glimpse your saviour. Keep it rare. */
    public static double remnantChance = 0.08;
    /** How many deaths a bound Remnant will take for you before it poofs. */
    public static int remnantLives = 3;
    /** Lives a rescued Remnant comes back with. Top it up with an allay and amethyst shards. */
    public static int remnantFreedLives = 1;
    /** How long an untamed glimpse waits before drifting off. */
    public static int remnantLingerSeconds = 90;
    /** Health (half-hearts) you come back with on a successful save. */
    public static float returnHealth = 12.0f;
    /** Unlimited loops. Set false to require a charge (not yet implemented in v0.1). */
    public static boolean unlimited = true;
    /** Give the curated kit instead of a full creative inventory. */
    public static boolean useSatchel = true;
    /** Refuse to run when more than one player is online, since the rewind is single-player safe only. */
    public static boolean singlePlayerOnly = true;

    public static int recordTicks() {
        return Math.max(40, recordSeconds * 20);
    }

    public static int replayWindowTicks() {
        return Math.max(20, replayWindowSeconds * 20);
    }

    /** Clamped to the recording: we cannot wind back further than we remember. */
    public static int rewindTicks() {
        return Math.min(recordTicks(), Math.max(40, rewindSeconds * 20));
    }

    public static int interventionTicks() {
        return Math.max(60, interventionSeconds * 20);
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("paradox.properties");
    }

    public static void load() {
        Path p = file();
        Properties props = new Properties();
        try {
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    props.load(in);
                }
                recordSeconds = getInt(props, "recordSeconds", recordSeconds);
                recordRadius = getDouble(props, "recordRadius", recordRadius);
                replaySpeed = getDouble(props, "replaySpeed", replaySpeed);
                replayWindowSeconds = getInt(props, "replayWindowSeconds", replayWindowSeconds);
                interventionSeconds = getInt(props, "interventionSeconds", interventionSeconds);
                maxTrackedEntities = getInt(props, "maxTrackedEntities", maxTrackedEntities);
                returnHealth = (float) getDouble(props, "returnHealth", returnHealth);
                rewindEnabled = getBool(props, "rewindEnabled", rewindEnabled);
                rewindSeconds = getInt(props, "rewindSeconds", rewindSeconds);
                remnantEnabled = getBool(props, "remnantEnabled", remnantEnabled);
                remnantChance = getDouble(props, "remnantChance", remnantChance);
                remnantLingerSeconds = getInt(props, "remnantLingerSeconds", remnantLingerSeconds);
                remnantLives = getInt(props, "remnantLives", remnantLives);
                remnantFreedLives = getInt(props, "remnantFreedLives", remnantFreedLives);
                journalRadius = getInt(props, "journalRadius", journalRadius);
                reliveInvulnerable = getBool(props, "reliveInvulnerable", reliveInvulnerable);
                unlimited = getBool(props, "unlimited", unlimited);
                useSatchel = getBool(props, "useSatchel", useSatchel);
                singlePlayerOnly = getBool(props, "singlePlayerOnly", singlePlayerOnly);
                // Write it straight back so keys added in later versions show up in the file
                // instead of silently sitting at their defaults where nobody can find them.
                save();
            } else {
                save();
            }
        } catch (IOException e) {
            Paradox.LOG.warn("[paradox] could not read config, using defaults", e);
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("recordSeconds", String.valueOf(recordSeconds));
        props.setProperty("recordRadius", String.valueOf(recordRadius));
        props.setProperty("replaySpeed", String.valueOf(replaySpeed));
        props.setProperty("replayWindowSeconds", String.valueOf(replayWindowSeconds));
        props.setProperty("interventionSeconds", String.valueOf(interventionSeconds));
        props.setProperty("maxTrackedEntities", String.valueOf(maxTrackedEntities));
        props.setProperty("returnHealth", String.valueOf(returnHealth));
        props.setProperty("rewindEnabled", String.valueOf(rewindEnabled));
        props.setProperty("rewindSeconds", String.valueOf(rewindSeconds));
        props.setProperty("remnantEnabled", String.valueOf(remnantEnabled));
        props.setProperty("remnantChance", String.valueOf(remnantChance));
        props.setProperty("remnantLingerSeconds", String.valueOf(remnantLingerSeconds));
        props.setProperty("remnantLives", String.valueOf(remnantLives));
        props.setProperty("remnantFreedLives", String.valueOf(remnantFreedLives));
        props.setProperty("journalRadius", String.valueOf(journalRadius));
        props.setProperty("reliveInvulnerable", String.valueOf(reliveInvulnerable));
        props.setProperty("unlimited", String.valueOf(unlimited));
        props.setProperty("useSatchel", String.valueOf(useSatchel));
        props.setProperty("singlePlayerOnly", String.valueOf(singlePlayerOnly));
        try {
            Path p = file();
            Files.createDirectories(p.getParent());
            try (OutputStream out = Files.newOutputStream(p)) {
                props.store(out, "Paradox - hardcore death rewind. Delete this file to reset.");
            }
        } catch (IOException e) {
            Paradox.LOG.warn("[paradox] could not write config", e);
        }
    }

    private static int getInt(Properties p, String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); }
        catch (NumberFormatException e) { return d; }
    }

    private static double getDouble(Properties p, String k, double d) {
        try { return Double.parseDouble(p.getProperty(k, String.valueOf(d)).trim()); }
        catch (NumberFormatException e) { return d; }
    }

    private static boolean getBool(Properties p, String k, boolean d) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d)).trim());
    }

    private ParadoxConfig() {}
}
