/*
 * Copyright (C) 2021 3TUSK
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

// SPDX-Identifier: LGPL-2.1-or-later

package org.teacon.sync;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileLocator;
import net.minecraftforge.forgespi.Environment;
import net.minecraftforge.forgespi.locating.IModFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class SyncedModLocator extends AbstractJarFileLocator {

    private static final Logger LOGGER = LogManager.getLogger("RemoteSync");

    private static final Gson GSON = new Gson();

    private final Consumer<String> progressFeed;

    private final PGPKeyStore keyStore;

    private CompletableFuture<Collection<Path>> fetchPathsTask;

    private boolean hasModSync = false;

    private final List<CompletableFuture<Collection<Path>>> otherSyncTasks;
    private final Path sigDir;

    public SyncedModLocator() throws Exception {
        this.progressFeed = Launcher.INSTANCE.environment().getProperty(Environment.Keys.PROGRESSMESSAGE.get()).orElse(msg -> {});
        final Path gameDir = Launcher.INSTANCE.environment().getProperty(IEnvironment.Keys.GAMEDIR.get()).orElse(Paths.get("."));
        final Path cfgPath = gameDir.resolve("remote_sync.json");
        final Config cfg;
        if (Files.exists(cfgPath)) {
            cfg = GSON.fromJson(Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8), Config.class);
        } else {
            LOGGER.warn("RemoteSync config remote_sync.json does not exist. All configurable values will use their default values instead.");
            cfg = new Config();
        }
        final Path baseDir = Files.createDirectories(gameDir.resolve(cfg.baseDir));
        this.sigDir = Files.createDirectories(baseDir.resolve(cfg.sigDir));
        final Path keyStorePath = gameDir.resolve(cfg.keyRingPath);
        this.keyStore = new PGPKeyStore(keyStorePath, cfg.keyServers, cfg.keyIds);
        this.keyStore.debugDump();
        this.otherSyncTasks = new ArrayList<>();
        for (TypeEntry typeEntry: cfg.syncFiles) {
            LOGGER.debug("Start to sync {} to {}.",typeEntry.name,typeEntry.saveDir);
            Path saveDirPath = Files.createDirectories(gameDir.resolve(typeEntry.saveDir));
            CompletableFuture<Collection<Path>> tempTask = CompletableFuture.supplyAsync(() -> {
                Path localCache = baseDir.resolve(typeEntry.localCache);
                try {
                    this.progressFeed.accept("RemoteSync: fetching "+typeEntry.name+" list");
                    // Intentionally do not use config value to ensure that the mod list is always up-to-date
                    return Utils.fetch(typeEntry.file, localCache, cfg.timeout, false);
                } catch (IOException e) {
                    LOGGER.warn("Failed to download "+typeEntry.name+" list, will try using locally cached "+typeEntry.name+" list instead. Files may be outdated.", e);
                    System.setProperty("org.teacon.sync.failed", "true");
                    try {
                        return FileChannel.open(localCache);
                    } catch (Exception e2) {
                        throw new RuntimeException("Failed to open locally cached "+typeEntry.name+" list", e2);
                    }
                }
            }).thenApplyAsync((fcModList) -> {
                try (Reader reader = Channels.newReader(fcModList, StandardCharsets.UTF_8)) {
                    return GSON.fromJson(reader, FileEntry[].class);
                } catch (JsonParseException e) {
                    LOGGER.warn("Error parsing "+typeEntry.name+" list", e);
                    throw e;
                } catch (IOException e) {
                    LOGGER.warn("Failed to open "+typeEntry.name+" list file", e);
                    throw new RuntimeException(e);
                }
            }).thenComposeAsync(entries -> {
                List<CompletableFuture<Void>> futures = Arrays.stream(entries).flatMap(e -> Stream.of(
                        Utils.downloadIfMissingAsync(saveDirPath.resolve(e.name), e.file, cfg.timeout, cfg.preferLocalCache, this.progressFeed),
                        Utils.downloadIfMissingAsync(sigDir.resolve(e.name + ".sig"), e.sig, cfg.timeout, cfg.preferLocalCache, this.progressFeed)
                )).toList();
                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenApply(v -> Arrays.stream(entries)
                                .map(it -> saveDirPath.resolve(it.name))
                                .filter(this::isValid).toList());
            });
            this.otherSyncTasks.add(tempTask);
            if (typeEntry.name.contains("mods")) {
                this.fetchPathsTask = tempTask;
                hasModSync = true;
            }
        }

    }

    private static PGPSignatureList getSigList(FileChannel fc) throws Exception {
        PGPSignatureList sigList;
        try (InputStream input = PGPUtil.getDecoderStream(Channels.newInputStream(fc))) {
            BcPGPObjectFactory factory = new BcPGPObjectFactory(input);
            Object o = factory.nextObject();
            if (o instanceof PGPCompressedData compressedData) {
                factory = new BcPGPObjectFactory(compressedData.getDataStream());
                sigList = (PGPSignatureList) factory.nextObject();
            } else {
                sigList = (PGPSignatureList) o;
            }
        }
        return sigList;
    }

        @Override
        public List<IModFile> scanMods() {
            List<IModFile> result = new ArrayList<>();
            for (Optional<IModFile> optionalIModFile : scanCandidates().map(this::createMod).toList()) {
                optionalIModFile.map(result::add);
            }
            return result;
        }

    @Override
    public Stream<Path> scanCandidates() {
        LOGGER.debug("Waiting for ALL sync task finished.");
        if (otherSyncTasks.size()>0) {
            for (CompletableFuture<Collection<Path>> otherSyncTask : otherSyncTasks) {
                otherSyncTask.join();
            }
        } else {
            LOGGER.error("NO TASK CONFIGURE, CHECK YOUR remote_sync.json");
            System.setProperty("org.teacon.sync.failed", "true");
            return Stream.empty();
        }
        if (hasModSync) {
            try {
                return this.fetchPathsTask.join().stream();
            } catch (Exception e) {
                LOGGER.error("Mod downloading worker encountered error. " +
                        "You may observe missing mods or outdated mods. ", e instanceof CompletionException ? e.getCause() : e);
                System.setProperty("org.teacon.sync.failed", "true");
                return Stream.empty();
            }
        }
        LOGGER.debug("Not mods sync task, return empty list.");
        return Stream.empty();
    }

    @Override
    public String name() {
        return "Remote Synced";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
    }

    private boolean isValid(Path modFile) {
        LOGGER.debug("Verifying {}", modFile.getFileName());
        this.progressFeed.accept("RemoteSync: verifying " + modFile.getFileName());
        final Path sigPath = sigDir.resolve(modFile.getFileName() + ".sig");
        try (FileChannel mod = FileChannel.open(modFile, StandardOpenOption.READ)) {
            try (FileChannel sig = FileChannel.open(sigPath, StandardOpenOption.READ)) {
                final PGPSignatureList sigList;
                try {
                    sigList = getSigList(sig);
                } catch (Exception e) {
                    LOGGER.warn("Failed to read signature for {}, verification automatically fails", modFile.getFileName());
                    return false;
                }
                if (sigList == null) {
                    LOGGER.warn("Failed to load any signature for {}, check if you downloaded the wrong file", modFile.getFileName());
                    return false;
                }
                final boolean pass = this.keyStore.verify(mod, sigList);
                if (pass) {
                    LOGGER.debug("Verification pass for {}", modFile.getFileName());
                } else {
                    LOGGER.warn("Verification fail for {}, will be excluded from loading", modFile.getFileName());
                    Files.deleteIfExists(modFile.toAbsolutePath().normalize());
                    Files.deleteIfExists(sigPath.toAbsolutePath().normalize());
                }
                return pass;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read {}, verification automatically fails", modFile.getFileName());
            return false;
        }
    }

}
