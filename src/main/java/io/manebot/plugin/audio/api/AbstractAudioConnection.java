package io.manebot.plugin.audio.api;

import io.manebot.plugin.audio.Audio;
import io.manebot.plugin.audio.channel.AudioChannel;
import io.manebot.plugin.audio.mixer.Mixer;
import io.manebot.plugin.audio.util.LoopTimer;
import io.manebot.virtual.Profiler;
import io.manebot.virtual.Virtual;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class AbstractAudioConnection implements AudioConnection {
    private final Object audioLock = new Object();
    private final Object enableLock = new Object();

    private final Audio audio;

    private final Collection<Mixer> mixers = new LinkedList<>();
    private final Collection<AudioChannel> channels = new LinkedList<>();

    private MixerProcessingTask task;

    private boolean connected = false;

    public AbstractAudioConnection(Audio audio) {
        this.audio = audio;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * Gets audio channels registered to this connection.
     * @return Immutable collection of audio channels.
     */
    @Override
    public Collection<AudioChannel> getChannels() {
        return Collections.unmodifiableCollection(channels);
    }

    /**
     * Gets mixers registered to this connection.
     * @return Immutable collection of audio channels.
     */
    @Override
    public Collection<Mixer> getMixers() {
        return Collections.unmodifiableCollection(mixers);
    }

    /**
     * Connects the AudioConnection.
     */
    @Override
    public void connect() {
        synchronized (enableLock) {
            if (connected) return;

            if (task != null)
                task = new MixerProcessingTask();

            Thread thread = new Thread(task);
            thread.start();

            connected = true;
        }
    }

    /**
     * Disconnects the AudioConnection.
     */
    @Override
    public void disconnect() {
        synchronized (enableLock) {
            if (!connected) return;

            for (Mixer mixer : getMixers())
                mixer.empty();

            connected = false;

            if (task != null) {
                try {
                    task.end();
                } catch (Exception e) {
                    throw new RuntimeException("Failed to end mixer processing task", e);
                }
            }
        }
    }

    @Override
    public void registerChannel(AudioChannel channel) {
        if (this.channels.add(channel)) {
            channel.getRegistrant().onChannelRegistered(channel);
        }
    }

    @Override
    public boolean unregisterChannel(AudioChannel channel) {
        if (this.channels.remove(channel)) {
            channel.getRegistrant().onChannelUnregistered(channel);
            channel.stopAll();

            return true;
        } else return false;
    }

    @Override
    public void registerMixer(Mixer mixer) {
        synchronized (audioLock) {
            if (mixers.stream().anyMatch(x -> x.getId().equalsIgnoreCase(mixer.getId())))
                throw new IllegalArgumentException("mixer", new IllegalStateException(mixer.getId()));

            mixers.add(mixer);
            audioLock.notifyAll();
        }

        mixer.getRegistrant().onMixerRegistered(mixer);
    }

    @Override
    public boolean unregisterMixer(Mixer mixer) {
        synchronized (audioLock) {
            if (mixers.remove(mixer)) {
                mixer.getRegistrant().onMixerUnregistered(mixer);

                // Make sure they cleaned everything up
                mixer.empty();

                return true;
            } else
                return false;
        }
    }

    private class MixerProcessingTask implements Runnable {
        private final CompletableFuture<Boolean> future = new CompletableFuture<>();

        private boolean running = false;

        private MixerProcessingTask() { }

        public void end() throws ExecutionException, InterruptedException {
            synchronized (audioLock) {
                if (!running) return;

                running = false;
                audioLock.notifyAll();
                future.get();
            }
        }

        @Override
        public void run() {
            try {
                running = true;

                Virtual.getInstance().currentProcess().setDescription("AudioThread");

                LoopTimer timer = new LoopTimer(audio.getLoopDelay(), audioLock);
                List<Mixer> playingMixers = new ArrayList<>();

                synchronized (audioLock) {
                    while (isConnected() && audio.getPlugin().isEnabled()) {
                        try (Profiler audioProfiler = Profiler.region("audio")) {
                            for (Mixer mixer : mixers) {
                                // If the mixer isn't playing anything we have no work to do.
                                if (!mixer.isPlaying()) {
                                    // If mixer is running, stop the mixer since no players are playing.
                                    if (mixer.isRunning()) {
                                        Logger.getGlobal().fine("Stopping mixer: " + mixer.getId() + "...");

                                        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
                                        mixer.setRunning(false);

                                        Logger.getGlobal().fine("Stopped mixer: " + mixer.getId() + ".");
                                    }
                                } else {
                                    playingMixers.add(mixer);
                                }
                            }

                            if (playingMixers.size() <= 0) {
                                audioLock.wait(1000L); // I see the point now
                                continue;
                            }

                            for (Mixer mixer : playingMixers) {
                                // Play audio on system
                                try {
                                    // Start the mixer if it's not running
                                    if (!mixer.isRunning()) {
                                        Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                                        mixer.setRunning(true);
                                    }

                                    mixer.processBuffer();
                                } catch (Exception ex) {
                                    Virtual.getInstance().getLogger().log(
                                            Level.SEVERE,
                                            "Problem processing Mixer buffer; emptying mixer",
                                            ex
                                    );

                                    mixer.empty();
                                }
                            }

                            // Clear the list to wipe clean
                            playingMixers.clear();

                            // Sleep for designated amount of time
                            try (Profiler sleepProfiler = Profiler.region("sleep")) {
                                timer.sleep();
                            } catch (InterruptedException e) {
                                Thread.yield();
                            }
                        }
                    }
                }
            } catch (Throwable ex) {
                Virtual.getInstance().getLogger().log(
                        Level.SEVERE,
                        "Problem in AudioPlugin processor main thread; shutting down audio",
                        ex
                );
            } finally {
                running = false;

                // Stop all mixers
                for (Mixer mixer : mixers)
                    mixer.setRunning(false);

                future.complete(true);
            }
        }
    }
}