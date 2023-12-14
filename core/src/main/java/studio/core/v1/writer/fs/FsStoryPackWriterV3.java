package studio.core.v1.writer.fs;


import studio.core.v1.utils.AESCBCCipher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class FsStoryPackWriterV3 {
    private static final String BOOT_FILENAME = "bt";

    public static byte[] convertV2toV3(byte[] data, byte[] deviceKey) {
        if (deviceKey == null || deviceKey.length == 0) {
            return data;
        }
        byte[] key = new byte[16];
        byte[] keyIV = new byte[16];
        System.arraycopy(deviceKey, 0, key, 0, 16);
        System.arraycopy(deviceKey, 16, keyIV, 0, 16);
        byte[] block = Arrays.copyOfRange(data, 0, Math.min(512, data.length));
        byte[] blockCiphered = AESCBCCipher.encrypt(block, key, keyIV);
        byte[] dataCiphered = new byte[Math.max(data.length, blockCiphered.length)];
        System.arraycopy(blockCiphered, 0, dataCiphered, 0, blockCiphered.length);
        System.arraycopy(data, block.length, dataCiphered, blockCiphered.length, data.length - block.length);
        return dataCiphered;
    }

    public static void addBootFile(Path packFolder, byte[] deviceUuid) throws IOException {
        Path btPath = packFolder.resolve(BOOT_FILENAME);
        byte[] btCipher = new byte[32];
        System.arraycopy(deviceUuid, 32, btCipher, 0, 32);
        Files.write(btPath, btCipher);
    }

}
