package com.particlesdevs.photoncamera.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import com.particlesdevs.photoncamera.app.PhotonCamera;
import com.particlesdevs.photoncamera.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multisample persistent store for the dynamic noise model.
 * Keyed by physicalID (camera/sensor) -> ISO bin (log2 stops) -> IsoBin.
 * A measurement list per physicalID prevents duplicate scenes (same
 * exposure/iso) from being committed more than once, avoiding bias.
 *
 * Binning is relative to the minimum ISO observed for each physicalID
 * (any value the device reports, e.g. 50/100/125/200...). The bin index is
 * {@code round(log2(iso / minIso))}. Whenever a new (smaller) minimum ISO is
 * found, the whole binned map for that physicalID is rebuilt (re-binned)
 * against the new reference so prior samples land in the correct stops.
 *
 * The store persists to JSON in the app's private storage
 * (files/noise_model/&lt;physicalID&gt;.json) so learned models survive across
 * process restarts. Loading is lazy (per physicalID) and saving is async.
 */
public class DynamicNoiseStore {
    private static final String DIR_NAME = "noise_model";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // Single low-priority daemon thread for async disk persistence.
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DynamicNoiseStore-IO");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        return t;
    });

    // physicalID -> (isoBin -> IsoBin)
    private final Map<Integer, Map<Integer, IsoBin>> store = new ConcurrentHashMap<>();
    // physicalID -> ordered raw samples (iso, s, o); source of truth for rebinning
    private final Map<Integer, ArrayList<RawSample>> rawSamples = new ConcurrentHashMap<>();
    // physicalID -> minimum ISO observed so far (dynamic bin reference)
    private final Map<Integer, Integer> minIso = new ConcurrentHashMap<>();
    // physicalID -> bounded recent-scene window (cooldown guard, in-memory only)
    private final Map<Integer, RecentWindow> recentScenes = new ConcurrentHashMap<>();
    // physicalID -> loaded flag (avoids re-reading disk on every commit)
    private final Set<Integer> loaded = ConcurrentHashMap.newKeySet();

    /**
     * Single S/O noise-model estimation sample.
     */
    public static final class NoiseEstimate {
        public final double s;
        public final double o;
        public NoiseEstimate(double s, double o) {
            this.s = s;
            this.o = o;
        }
    }

    /**
     * Adaptive moving-average window for one ISO bin.
     * Grows up to {@link #MAX_WINDOW} estimations; once filled with 10 estimations
     * it always keeps a moving average of the last 10 elements to smooth out
     * estimator fluctuations without over-weighting stale history.
     */
    public static final class IsoBin {
        public static final int MAX_WINDOW = 10;
        private final ArrayList<NoiseEstimate> samples = new ArrayList<>(MAX_WINDOW);
        // Total estimations ever committed to this bin (for diagnostics).
        private int totalEstimations = 0;

        public void add(double s, double o) {
            samples.add(new NoiseEstimate(s, o));
            totalEstimations++;
            // Adaptive moving window: cap at MAX_WINDOW. Before 10 estimations the
            // window grows with the data (averaging all available); once 10 is
            // reached it becomes a fixed moving average of the last 10.
            if (samples.size() > MAX_WINDOW) {
                samples.remove(0);
            }
        }

        public NoiseEstimate average() {
            if (samples.isEmpty()) return null;
            double ss = 0.0, so = 0.0;
            for (NoiseEstimate n : samples) {
                ss += n.s;
                so += n.o;
            }
            int n = samples.size();
            return new NoiseEstimate(ss / n, so / n);
        }

        public int count() {
            return samples.size();
        }

        public int totalEstimations() {
            return totalEstimations;
        }
    }

    /**
     * Bounded FIFO window of recently committed scene keys. A scene is
     * blocked while its key is still in the window and allowed again once
     * {@link #COOLDOWN} other scenes have pushed it out. This replaces the
     * old permanent dedup set with a cooldown so repeated identical
     * exposure/iso scenes can refresh the moving average periodically.
     *
     * Not persisted: cooldown resets each process start, which keeps the
     * learned model fresh instead of permanently freezing a scene out.
     */
    private static final class RecentWindow {
        // Number of distinct scenes that must pass before the same scene
        // is accepted again. 3 → a scene re-commits after ~3 other scenes.
        static final int COOLDOWN = 3;
        private final java.util.LinkedHashSet<Long> recent =
                new java.util.LinkedHashSet<>(COOLDOWN + 1);

        /** @return true if the scene may be committed (cooldown elapsed / new). */
        synchronized boolean allow(long key) {
            if (recent.contains(key)) return false;
            recent.add(key);
            if (recent.size() > COOLDOWN) {
                java.util.Iterator<Long> it = recent.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
            return true;
        }
    }

    private static long sceneKey(double exposureTime, int iso) {
        long expNs = Math.round(exposureTime * 1.0e9);
        return ((long) iso << 32) ^ expNs;
    }

    private static File dir() {
        File base = PhotonCamera.getSettingsManagerStatic().getContext().getFilesDir();
        File d = new File(base, DIR_NAME);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(int physicalID) {
        return new File(dir(), physicalID + ".json");
    }

    /** Serialized representation of one physicalID's state. */
    private static final class StoreDto {
        @SerializedName("physicalID")
        int physicalID;
        @SerializedName("minIso")
        int minIso;
        @SerializedName("samples")
        List<RawSample> samples;
    }

    /**
     * Lazily load a physicalID's state from disk on first access. Safe to
     * call repeatedly; only performs I/O once per physicalID per process.
     * The cooldown window is intentionally not restored so scenes can
     * refresh the model after a restart.
     */
    private void ensureLoaded(int physicalID) {
        if (!loaded.add(physicalID)) return;
        File f = fileFor(physicalID);
        if (!f.exists()) return;
        try (FileReader r = new FileReader(f)) {
            StoreDto dto = GSON.fromJson(r, StoreDto.class);
            if (dto == null) return;
            if (dto.minIso > 0) minIso.put(physicalID, dto.minIso);
            if (dto.samples != null) {
                rawSamples.put(physicalID, new ArrayList<>(dto.samples));
            }
            Integer base = minIso.get(physicalID);
            if (base != null && rawSamples.get(physicalID) != null) {
                rebuildBins(physicalID, base);
            }
            Log.d("DynamicNoiseStore", "Loaded pid=" + physicalID
                    + " minIso=" + dto.minIso
                    + " samples=" + (dto.samples == null ? 0 : dto.samples.size()));
        } catch (Exception e) {
            Log.d("DynamicNoiseStore", "Load failed pid=" + physicalID + ": " + e);
        }
    }

    /**
     * Asynchronously persist a physicalID's state. Serializes rawSamples
     * and minIso, then writes atomically. The cooldown window is not
     * persisted so scenes can re-commit after a process restart.
     */
    private void saveAsync(int physicalID) {
        ArrayList<RawSample> samples = rawSamples.get(physicalID);
        Integer base = minIso.get(physicalID);
        if (samples == null || base == null) return;
        final StoreDto dto = new StoreDto();
        dto.physicalID = physicalID;
        dto.minIso = base;
        dto.samples = new ArrayList<>(samples);
        ioExecutor.submit(() -> {
            File f = fileFor(physicalID);
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                GSON.toJson(dto, w);
                if (f.exists()) f.delete();
                tmp.renameTo(f);
            } catch (IOException e) {
                Log.d("DynamicNoiseStore", "Save failed pid=" + physicalID + ": " + e);
                tmp.delete();
            }
        });
    }

    /**
     * Log2 ISO bin relative to the current dynamic minimum ISO.
     */
    private static int isoBin(int iso, int baseIso) {
        if (iso <= 0) iso = baseIso;
        if (baseIso <= 0) baseIso = iso;
        double bin = Math.log((double) iso / (double) baseIso) / Math.log(2.0);
        return (int) Math.round(bin);
    }

    /**
     * Rebuild the binned map for a physicalID from its raw samples against the
     * supplied minimum ISO. Preserves insertion order within each bin so the
     * moving-window average stays temporally consistent.
     */
    private void rebuildBins(int physicalID, int newMinIso) {
        Map<Integer, IsoBin> bins = new ConcurrentHashMap<>();
        ArrayList<RawSample> samples = rawSamples.get(physicalID);
        if (samples != null) {
            for (RawSample rs : samples) {
                int bin = isoBin(rs.iso, newMinIso);
                IsoBin ib = bins.computeIfAbsent(bin, k -> new IsoBin());
                ib.add(rs.s, rs.o);
            }
        }
        store.put(physicalID, bins);
    }

    /**
     * First commits the estimation to the noise map (unless this scene was
     * already measured), then returns the blended (averaged) S/O for the
     * resolved ISO bin. Committing before reading ensures the new sample
     * participates in the average while the measurement-list guard avoids
     * duplicate-scene bias. If the committed ISO is a new minimum, the map
     * is rebuilt against the new reference before reading back. State is
     * persisted to disk asynchronously after a non-duplicate commit.
     *
     * @return blended estimate, or null if the bin is still empty.
     */
    public NoiseEstimate commitAndGet(int physicalID, int iso, int analogIso,
                                      double s, double o,
                                      double exposureTime) {
        ensureLoaded(physicalID);

        RecentWindow window = recentScenes.computeIfAbsent(physicalID,
                k -> new RecentWindow());
        long key = sceneKey(exposureTime, iso);
        boolean allowed = window.allow(key);
        if (allowed) {
            ArrayList<RawSample> samples =
                    rawSamples.computeIfAbsent(physicalID, k -> new ArrayList<>());
            samples.add(new RawSample(iso, s, o));

            Integer curMin = minIso.get(physicalID);
            if (curMin == null || iso < curMin) {
                // New minimum ISO found: rebuild the whole map against it.
                minIso.put(physicalID, iso);
                rebuildBins(physicalID, iso);
                Log.d("DynamicNoiseStore", "New min ISO=" + iso
                        + " for pid=" + physicalID + ", rebuilt map ("
                        + samples.size() + " samples)");
            } else {
                Map<Integer, IsoBin> bins = store.computeIfAbsent(physicalID,
                        k -> new ConcurrentHashMap<>());
                int bin = isoBin(iso, curMin);
                IsoBin ib = bins.computeIfAbsent(bin, k -> new IsoBin());
                ib.add(s, o);
                Log.d("DynamicNoiseStore", "Committed pid=" + physicalID
                        + " iso=" + iso + " bin=" + bin + " S=" + s + " O=" + o
                        + " count=" + ib.count() + " total=" + ib.totalEstimations());
            }
            saveAsync(physicalID);
        } else {
            Log.d("DynamicNoiseStore", "Scene in cooldown, skipped pid=" + physicalID
                    + " iso=" + iso + " exp=" + exposureTime);
        }

        Integer baseIso = minIso.get(physicalID);
        if (baseIso == null) return null;
        Map<Integer, IsoBin> bins = store.get(physicalID);
        if (bins == null) return null;
        IsoBin ib = bins.get(isoBin(iso, baseIso));
        if (ib == null) return null;
        return ib.average();
    }

    public IsoBin getBin(int physicalID, int iso) {
        ensureLoaded(physicalID);
        Integer baseIso = minIso.get(physicalID);
        if (baseIso == null) return null;
        Map<Integer, IsoBin> bins = store.get(physicalID);
        if (bins == null) return null;
        return bins.get(isoBin(iso, baseIso));
    }

    /** Raw sample kept for re-binning when a new minimum ISO is discovered.
     *  Also serialized to JSON via Gson. */
    private static final class RawSample {
        @SerializedName("iso")
        final int iso;
        @SerializedName("s")
        final double s;
        @SerializedName("o")
        final double o;
        RawSample(int iso, double s, double o) {
            this.iso = iso;
            this.s = s;
            this.o = o;
        }
    }

    // Process-wide persistent dynamic noise model store.
    public static final DynamicNoiseStore dynamicNoiseStore = new DynamicNoiseStore();
}
