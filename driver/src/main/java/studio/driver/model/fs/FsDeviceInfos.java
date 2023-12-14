/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.driver.model.fs;

import studio.core.v1.utils.SecurityUtils;
import studio.driver.model.DeviceInfos;

public class FsDeviceInfos extends DeviceInfos {

    private byte[] deviceKey;
    private long sdCardSizeInBytes;
    private long usedSpaceInBytes;

    public byte[] getDeviceKey() {
        return deviceKey;
    }

    public void setDeviceKey(byte[] deviceId) {
        this.deviceKey = deviceId;
    }

    public long getSdCardSizeInBytes() {
        return sdCardSizeInBytes;
    }

    public void setSdCardSizeInBytes(long sdCardSizeInBytes) {
        this.sdCardSizeInBytes = sdCardSizeInBytes;
    }

    public long getUsedSpaceInBytes() {
        return usedSpaceInBytes;
    }

    public void setUsedSpaceInBytes(long usedSpaceInBytes) {
        this.usedSpaceInBytes = usedSpaceInBytes;
    }

    @Override
    public String toString() {
        return "FsDeviceInfos{" + "uuid=" + SecurityUtils.encodeHex(deviceKey) + ", firmwareMajor=" + getFirmwareMajor()
                + ", firmwareMinor=" + getFirmwareMinor() + ", serialNumber='" + getSerialNumber() + '\''
                + ", sdCardSizeInBytes=" + sdCardSizeInBytes + ", usedSpaceInBytes=" + usedSpaceInBytes + '}';
    }
}
