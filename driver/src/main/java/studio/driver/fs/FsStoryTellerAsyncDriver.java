/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.fs;

import static studio.core.v1.writer.fs.FsStoryPackWriter.transformUuid;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.usb4java.Device;

import studio.core.v1.utils.AESCBCCipher;
import studio.core.v1.utils.SecurityUtils;
import studio.core.v1.utils.XXTEACipher;
import studio.core.v1.utils.exception.StoryTellerException;
import studio.core.v1.utils.stream.ThrowingConsumer;
import studio.core.v1.writer.fs.FsStoryPackWriter;
import studio.core.v1.writer.fs.FsStoryPackWriterV3;
import studio.driver.DeviceVersion;
import studio.driver.LibUsbDetectionHelper;
import studio.driver.StoryTellerAsyncDriver;
import studio.driver.event.DevicePluggedListener;
import studio.driver.event.DeviceUnpluggedListener;
import studio.driver.event.TransferProgressListener;
import studio.driver.model.TransferStatus;
import studio.driver.model.fs.FsDeviceInfos;
import studio.driver.model.fs.FsStoryPackInfos;

public class FsStoryTellerAsyncDriver implements StoryTellerAsyncDriver<FsDeviceInfos, FsStoryPackInfos> {

    private static final Logger LOGGER = LogManager.getLogger(FsStoryTellerAsyncDriver.class);

    private static final String DEVICE_METADATA_FILENAME = ".md";
    private static final String PACK_INDEX_FILENAME = ".pi";
    private static final String CONTENT_FOLDER = ".content";
    private static final String NODE_INDEX_FILENAME = "ni";
    private static final String NIGHT_MODE_FILENAME = "nm";

    private static final long FS_MOUNTPOINT_POLL_DELAY = 1000L;
    private static final long FS_MOUNTPOINT_RETRY = 10;


    private Device device = null;
    private Path partitionMountPoint = null;
    private List<DevicePluggedListener> pluggedlisteners = new ArrayList<>();
    private List<DeviceUnpluggedListener> unpluggedlisteners = new ArrayList<>();

    public FsStoryTellerAsyncDriver() {
        // Initialize libusb, handle and propagate hotplug events
        LOGGER.debug("Registering hotplug listener");
        LibUsbDetectionHelper.initializeLibUsb(DeviceVersion.DEVICE_VERSION_2, //
                device2 -> {
                    // Wait for a partition to be mounted which contains the .md file
                    LOGGER.debug("Waiting for device partition...");
                    for (int i = 0; i < FS_MOUNTPOINT_RETRY && partitionMountPoint == null; i++) {
                        try {
                            Thread.sleep(FS_MOUNTPOINT_POLL_DELAY);
                            DeviceUtils.listMountPoints().forEach(path -> {
                                LOGGER.trace("Looking for .md in {}", path);
                                if (Files.exists(path.resolve(DEVICE_METADATA_FILENAME))) {
                                    partitionMountPoint = path;
                                    LOGGER.info("FS device partition located: {}", partitionMountPoint);
                                }
                            });
                        } catch (InterruptedException e) {
                            LOGGER.error("Failed to locate device partition", e);
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (partitionMountPoint == null) {
                        throw new StoryTellerException("Could not locate device partition");
                    }
                    // Update device reference
                    FsStoryTellerAsyncDriver.this.device = device2;
                    // Notify listeners
                    FsStoryTellerAsyncDriver.this.pluggedlisteners.forEach(l -> l.onDevicePlugged(device2));
                }, //
                device2 -> {
                    // Update device reference
                    FsStoryTellerAsyncDriver.this.device = null;
                    FsStoryTellerAsyncDriver.this.partitionMountPoint = null;
                    // Notify listeners
                    FsStoryTellerAsyncDriver.this.unpluggedlisteners.forEach(l -> l.onDeviceUnplugged(device2));
                });
    }

    public void registerDeviceListener(DevicePluggedListener pluggedlistener, DeviceUnpluggedListener unpluggedlistener) {
        this.pluggedlisteners.add(pluggedlistener);
        this.unpluggedlisteners.add(unpluggedlistener);
        if (this.device != null) {
            pluggedlistener.onDevicePlugged(this.device);
        }
    }

    public CompletionStage<FsDeviceInfos> getDeviceInfos() {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }
        FsDeviceInfos infos = new FsDeviceInfos();
        Path mdFile = this.partitionMountPoint.resolve(DEVICE_METADATA_FILENAME);
        LOGGER.trace("Reading device infos from file: {}", mdFile);

        try (DataInputStream is = new DataInputStream(new BufferedInputStream(Files.newInputStream(mdFile)))) {

            // SD card size and used space
            FileStore mdFd = Files.getFileStore(mdFile);
            long sdCardTotalSpace = mdFd.getTotalSpace();
            long sdCardUsedSpace = mdFd.getTotalSpace() - mdFd.getUnallocatedSpace();
            double percent = Math.round(100d * 100d * sdCardUsedSpace / sdCardTotalSpace) / 100d;
            infos.setSdCardSizeInBytes(sdCardTotalSpace);
            infos.setUsedSpaceInBytes(sdCardUsedSpace);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("SD card used : {}% ({} / {})", percent, FileUtils.readableByteSize(sdCardUsedSpace), FileUtils.readableByteSize(sdCardTotalSpace));
            }

            // MD file format version
            short mdVersion = DeviceUtils.readLittleEndianShort(is);
            LOGGER.trace("Device metadata format version: {}", mdVersion);

            if (mdVersion >= 1 && mdVersion <= 3) {
                return this.getDeviceInfosMeta1to3(infos, is);
            } else if (mdVersion == 6) {
                return this.getDeviceInfosMeta6(infos, is);
            } else {
                return CompletableFuture.failedFuture(new StoryTellerException("Unsupported device metadata format version: " + mdVersion));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to read device metadata on partition", e));
        }
    }

    public CompletionStage<FsDeviceInfos> getDeviceInfosMeta1to3(FsDeviceInfos infos, DataInputStream is) throws IOException {
        // Firmware version
        is.skipBytes(4);
        short major = DeviceUtils.readLittleEndianShort(is);
        short minor = DeviceUtils.readLittleEndianShort(is);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.debug("Firmware version: {}.{}", major, minor);

        // Serial number
        String serialNumber = null;
        long sn = DeviceUtils.readBigEndianLong(is);
        if (sn != 0L && sn != -1L && sn != -4294967296L) {
            serialNumber = String.format("%014d", sn);
            LOGGER.debug("Serial Number: {}", serialNumber);
        } else {
            LOGGER.warn("No serial number in SPI");
        }
        infos.setSerialNumber(serialNumber);

        // UUID
        is.skipBytes(238);
        byte[] deviceId = is.readNBytes(256);
        infos.setDeviceKey(deviceId);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("UUID: {}", SecurityUtils.encodeHex(deviceId));
        }

        return CompletableFuture.completedFuture(infos);
    }

    public CompletionStage<FsDeviceInfos> getDeviceInfosMeta6(FsDeviceInfos infos, DataInputStream is) throws IOException {
        short major = DeviceUtils.readAsciiToShort(is, 1);
        is.skipBytes(1);
        short minor = DeviceUtils.readAsciiToShort(is, 1);
        infos.setFirmwareMajor(major);
        infos.setFirmwareMinor(minor);
        LOGGER.debug("Firmware version: " + major + "." + minor);

        // Serial number
        is.skipBytes(21);
        long sn = DeviceUtils.readAsciiToLong(is, 14);
        String serialNumber = String.format("%014d", sn);
        LOGGER.debug("Serial Number: " + serialNumber);
        infos.setSerialNumber(serialNumber);

        // UUID
        byte[] snb = String.valueOf(sn).getBytes(StandardCharsets.UTF_8);
        is.skipBytes(24);
        byte[] key = is.readNBytes(32);
        byte[] deviceKey = new byte[64];
        System.arraycopy(snb, 0, deviceKey, 0 , 14);
        System.arraycopy(snb, 0, deviceKey, 24 , 8);
        System.arraycopy(key, 0, deviceKey, 32 , 32);
        infos.setDeviceKey(deviceKey);

        is.close();
        return CompletableFuture.completedFuture(infos);
    }

    public CompletionStage<List<FsStoryPackInfos>> getPacksList() {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }

        return readPackIndex()
                .thenApply(packUUIDs -> {
                    try {
                        LOGGER.debug("Number of packs in index: {}", packUUIDs.size());
                        List<FsStoryPackInfos> packs = new ArrayList<>();
                        for (UUID packUUID : packUUIDs) {
                            FsStoryPackInfos packInfos = new FsStoryPackInfos();
                            packInfos.setUuid(packUUID);
                            LOGGER.debug("Pack UUID: {}", packUUID);

                            // Compute .content folder (last 4 bytes of UUID)
                            String folderName = transformUuid(packUUID.toString());
                            Path packPath = this.partitionMountPoint.resolve(CONTENT_FOLDER).resolve(folderName);
                            packInfos.setFolderName(folderName);

                            // Open 'ni' file
                            Path niPath = packPath.resolve(NODE_INDEX_FILENAME);
                            try (InputStream niDis = new BufferedInputStream(Files.newInputStream(niPath))) {
                                ByteBuffer bb = ByteBuffer.wrap(niDis.readNBytes(512)).order(ByteOrder.LITTLE_ENDIAN);
                                short version = bb.getShort(2);
                                packInfos.setVersion(version);
                                LOGGER.debug("Pack version: {}", version);
                            }
                            // Night mode is available if file 'nm' exists
                            packInfos.setNightModeAvailable(Files.exists(packPath.resolve(NIGHT_MODE_FILENAME)));
                            // Compute folder size
                            packInfos.setSizeInBytes(FileUtils.getFolderSize(packPath));

                            packs.add(packInfos);
                        }
                        return packs;
                    } catch (IOException e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    private CompletionStage<List<UUID>> readPackIndex() {
        return CompletableFuture.supplyAsync(() -> {
            List<UUID> packUUIDs = new ArrayList<>();
            Path piFile = this.partitionMountPoint.resolve(PACK_INDEX_FILENAME);
            LOGGER.trace("Reading packs index from file: {}", piFile);
            try {
                ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(piFile));
                while (bb.hasRemaining()) {
                    long high = bb.getLong();
                    long low = bb.getLong();
                    packUUIDs.add(new UUID(high, low));
                }
                return packUUIDs;
            } catch (IOException e) {
                throw new StoryTellerException("Failed to read pack index on device partition", e);
            }
        });
    }


    public CompletionStage<Boolean> reorderPacks(List<String> uuids) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }

        return readPackIndex()
                .thenCompose(packUUIDs -> {
                    try {
                        boolean allUUIDsAreOnDevice = uuids.stream()
                                .allMatch(uuid -> packUUIDs.stream().anyMatch(p -> p.equals(UUID.fromString(uuid))));
                        if (allUUIDsAreOnDevice) {
                            // Reorder list according to uuids list
                            packUUIDs.sort(Comparator.comparingInt(p -> uuids.indexOf(p.toString())));
                            // Write pack index
                            return writePackIndex(packUUIDs);
                        } else {
                            throw new StoryTellerException("Packs on device do not match UUIDs");
                        }
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    public CompletionStage<Boolean> deletePack(String uuid) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }

        return readPackIndex()
                .thenCompose(packUUIDs -> {
                    try {
                        // Look for UUID in packs index
                        Optional<UUID> matched = packUUIDs.stream().filter(p -> p.equals(UUID.fromString(uuid))).findFirst();
                        if (matched.isPresent()) {
                            LOGGER.debug("Found pack with uuid: {}", uuid);
                            // Remove from index
                            packUUIDs.remove(matched.get());
                            // Write pack index
                            return writePackIndex(packUUIDs)
                                    .thenCompose(ok -> {
                                        // Generate folder name
                                        String folderName = transformUuid(uuid);
                                        Path folderPath = this.partitionMountPoint.resolve(CONTENT_FOLDER).resolve(folderName);
                                        LOGGER.debug("Removing pack folder: {}", folderPath);
                                        try {
                                            FileUtils.deleteDirectory(folderPath);
                                            return CompletableFuture.completedFuture(ok);
                                        } catch (IOException e) {
                                            return CompletableFuture.failedFuture(new StoryTellerException("Failed to delete pack folder on device partition", e));
                                        }
                                    });
                        } else {
                            throw new StoryTellerException("Pack not found");
                        }
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to read pack metadata on device partition", e);
                    }
                });
    }

    private CompletionStage<Boolean> writePackIndex(List<UUID> packUUIDs) {
        try {
            Path piFile = this.partitionMountPoint.resolve(PACK_INDEX_FILENAME);

            LOGGER.trace("Replacing pack index file: {}", piFile);
            ByteBuffer bb = ByteBuffer.allocate(16 * packUUIDs.size());
            for (UUID packUUID : packUUIDs) {
                bb.putLong(packUUID.getMostSignificantBits());
                bb.putLong(packUUID.getLeastSignificantBits());
            }
            Files.write(piFile, bb.array());

            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new StoryTellerException("Failed to write pack index on device partition", e));
        }
    }

    public CompletionStage<TransferStatus> downloadPack(String uuid, Path destPath, TransferProgressListener listener) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }
        return readPackIndex()
                .thenCompose(packUUIDs -> CompletableFuture.supplyAsync(() -> {
                    // Look for UUID in packs index
                    Optional<UUID> matched = packUUIDs.stream().filter(p -> p.equals(UUID.fromString(uuid))).findFirst();
                    if (matched.isEmpty()) {
                        throw new StoryTellerException("Pack not found");
                    }
                    LOGGER.debug("Found pack with uuid: {}", uuid);

                    // Generate folder name
                    String folderName = transformUuid(uuid);
                    Path sourceFolder = this.partitionMountPoint.resolve(CONTENT_FOLDER).resolve(folderName);
                    LOGGER.trace("Downloading pack folder: {}", sourceFolder);
                    if (Files.notExists(sourceFolder)) {
                        throw new StoryTellerException("Pack folder not found");
                    }

                    try {
                        // Destination folder
                        Path destFolder = destPath.resolve(uuid);
                        // Copy folder with progress tracking
                        return copyPackFolder(sourceFolder, destFolder, 2, new byte[0], listener);
                    } catch (IOException e) {
                        throw new StoryTellerException("Failed to copy pack from device", e);
                    }
                }));
    }

    private static class FsDeviceInfosTransferStatus {
        private final FsDeviceInfos deviceInfos;
        private final TransferStatus status;

        public FsDeviceInfosTransferStatus(FsDeviceInfos deviceInfos, TransferStatus status) {
            this.deviceInfos = deviceInfos;
            this.status = status;
        }

        public FsDeviceInfos getDeviceInfos() {
            return this.deviceInfos;
        }

        public TransferStatus getStatus() {
            return this.status;
        }
    }

    public CompletionStage<TransferStatus> uploadPack(String uuid, Path inputPath, TransferProgressListener listener) {
        if (this.device == null || this.partitionMountPoint == null) {
            return CompletableFuture.failedFuture(noDevicePluggedException());
        }

        try {
            // Check free space
            long folderSize = FileUtils.getFolderSize(inputPath);
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Pack folder size: {}", FileUtils.readableByteSize(folderSize));
            }
            Path mdFile = this.partitionMountPoint.resolve(DEVICE_METADATA_FILENAME);
            long freeSpace = Files.getFileStore(mdFile).getUsableSpace();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("SD free space: {}", FileUtils.readableByteSize(freeSpace));
            }
            if (freeSpace < folderSize) {
                throw new StoryTellerException("Not enough free space on the device");
            }

            // Generate folder name
            String folderName = transformUuid(uuid);
            Path folderPath = this.partitionMountPoint.resolve(CONTENT_FOLDER).resolve(folderName);
            LOGGER.debug("Uploading pack to folder: {}", folderName);

            // Copy folder with progress tracking
            return getDeviceInfos().thenApplyAsync(deviceInfos -> {
                try {
                    return new FsDeviceInfosTransferStatus(
                            deviceInfos,
                            copyPackFolder(inputPath, folderPath, deviceInfos.getFirmwareMajor(), deviceInfos.getDeviceKey(), listener)
                    );
                } catch (IOException e) {
                    throw new StoryTellerException("Failed to copy pack from device", e);
                }
            }).thenApply(fsDeviceInfosTransferStatus -> {
                FsDeviceInfos deviceInfos = fsDeviceInfosTransferStatus.getDeviceInfos();
                TransferStatus status = fsDeviceInfosTransferStatus.getStatus();
                // When transfer is complete, generate device-specific boot file from device UUID
                LOGGER.debug("Generating device-specific boot file");
                try {
                    if (deviceInfos.getFirmwareMajor() == 3) {
                        FsStoryPackWriterV3.addBootFile(folderPath, deviceInfos.getDeviceKey());
                    } else {
                        FsStoryPackWriter.addBootFile(folderPath, deviceInfos.getDeviceKey());
                    }
                    return status;
                } catch (IOException e) {
                    throw new StoryTellerException("Failed to generate device-specific boot file", e);
                }
            }).thenCompose(status -> {
                // Finally, add pack UUID to index
                LOGGER.debug("Add pack uuid to index");
                return readPackIndex().thenCompose(packUUIDs -> {
                    try {
                        // Add UUID in packs index
                        packUUIDs.add(UUID.fromString(uuid));
                        // Write pack index
                        return writePackIndex(packUUIDs).thenApply(ok -> status);
                    } catch (Exception e) {
                        throw new StoryTellerException("Failed to write pack metadata on device partition", e);
                    }
                });
            });
        } catch (IOException e) {
            throw new StoryTellerException("Failed to copy pack to device", e);
        }
    }

    private TransferStatus copyPackFolder(Path sourceFolder, Path destFolder, int fwVersion, byte[] deviceKey, TransferProgressListener listener)
            throws IOException {
        // Keep track of transferred bytes and elapsed time
        final long startTime = System.currentTimeMillis();
        AtomicLong transferred = new AtomicLong(0);
        long folderSize = FileUtils.getFolderSize(sourceFolder);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Pack folder size: {}", FileUtils.readableByteSize(folderSize));
        }
        // Target directory
        Files.createDirectories(destFolder);
        // Copy folders and files
        try (Stream<Path> paths = Files.walk(sourceFolder)) {
            paths.forEach(ThrowingConsumer.unchecked(s -> {
                Path d = destFolder.resolve(sourceFolder.relativize(s));
                // Copy directory
                if (Files.isDirectory(s)) {
                    LOGGER.debug("Creating directory {}", d);
                    Files.createDirectories(d);
                } else {
                    // Copy files
                    long fileSize = FileUtils.getFileSize(s);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Copying file {} ({}) to {}", s.getFileName(),
                                FileUtils.readableByteSize(fileSize), d);
                    }
                    if (fwVersion != 3 || s.toString().endsWith(File.separator + NODE_INDEX_FILENAME) || s.toString().endsWith(File.separator + NIGHT_MODE_FILENAME)) {
                        Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        InputStream in = new BufferedInputStream(new FileInputStream(s.toFile()));
                        OutputStream out = new BufferedOutputStream(new FileOutputStream(d.toFile()));
                        byte[] buffer = new byte[512];
                        int lengthRead;
                        boolean hasCiphered = false;
                        while ((lengthRead = in.read(buffer)) > 0) {
                            if (!hasCiphered) {
                                if (lengthRead < 512) {
                                    byte[] tmpBuffer = buffer;
                                    buffer = new byte[lengthRead];
                                    System.arraycopy(tmpBuffer, 0, buffer, 0, lengthRead);
                                }
                                buffer = XXTEACipher.cipherCommonKey(XXTEACipher.CipherMode.DECIPHER, buffer);
                                buffer = AESCBCCipher.cipher(buffer, deviceKey);
                                lengthRead = buffer.length;
                                hasCiphered = true;
                            }
                            out.write(buffer, 0, lengthRead);
                            out.flush();
                        }
                        out.close();
                        in.close();
                    }
                    // Compute progress and speed
                    long xferred = transferred.addAndGet(fileSize);
                    long elapsed = System.currentTimeMillis() - startTime;
                    double speed = xferred / (elapsed / 1000.0);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Transferred {} in {} ms", FileUtils.readableByteSize(xferred), elapsed);
                        LOGGER.trace("Average speed = {}/sec", FileUtils.readableByteSize((long) speed));
                    }
                    TransferStatus status = new TransferStatus(xferred, folderSize, speed);

                    // Call (optional) listener with transfer status
                    if (listener != null) {
                        CompletableFuture.runAsync(() -> listener.onProgress(status));
                    }
                }
            }));
        }
        return new TransferStatus(transferred.get(), folderSize, 0.0);
    }

    public CompletionStage<Void> dump(Path outputPath) {
        // Not supported
        LOGGER.warn("Not supported : dump");
        return CompletableFuture.completedFuture(null);
    }

    private StoryTellerException noDevicePluggedException() {
        return new StoryTellerException("No device plugged");
    }
}
