/*
 * Copyright (c) 2026 AltaStata Inc. All rights reserved.
 *
 * This software is dual-licensed. It is licensed under the Business Source License 1.1 
 * (BSL) for open use and evaluation, with an eventual transition to the Apache 2.0 
 * license on the Change Date.
 * 
 * PATENT NOTICE: Protected by US Patent No. 10,693,660.
 *
 * For the full license text, see the LICENSE.md file in the root of the repository,
 * or https://github.com/AltaStata/sovereign-data-fabric/blob/main/LICENSE.md
 */

package com.altastata.api;

import com.altastata.filesystem.securecloud.ChannelEncryptionServiceBuilder$;
import com.altastata.filesystem.securecloud.ChannelEncryptionService;
import com.altastata.utils.Account;

import static com.altastata.filesystem.common.VersionAttributes.account;

/**
 * Provides channel-level buffer encryption and decryption using AES-GCM.
 * 
 * Leverages the associated {@link com.altastata.filesystem.common.CloudFile} keys as a secure communication channel.
 *
 * @author AltaStata
 */
public class AltaStataChannelEncryptionService {

	private static ChannelEncryptionServiceBuilder$ channelEncryptionServiceBuilder = ChannelEncryptionServiceBuilder$.MODULE$;

	private ChannelEncryptionService channelEncryptionService = null;

	/**
	 * Create AltaStata Key Service
	 *
	 * @param cloudFilePath The file path on the cloud
	 * @param snapshotTime File versions creation time should match this snapshot or be last nearest. Use current time for default.
	 */
	protected AltaStataChannelEncryptionService(String cloudFilePath, Long snapshotTime, Account account) {
		channelEncryptionService = channelEncryptionServiceBuilder.getChannelEncryptionService(cloudFilePath, snapshotTime, account);
	}
	
	/**
	 * Encrypt clearText using the service key
	 * 
	 * @param clearText buffer
	 * @return
	 */
	public byte[] encryptByteArray(byte[] clearText) {
		return channelEncryptionService.encryptByteArray(clearText);
	}
	
	/**
	 * Decrypt cyberText using the service key
	 * 
	 * @param cyberText buffer
	 * @return
	 */
	public byte[] decryptByteArray(byte[] cyberText) {
		return channelEncryptionService.decryptByteArray(cyberText);
	}
}
