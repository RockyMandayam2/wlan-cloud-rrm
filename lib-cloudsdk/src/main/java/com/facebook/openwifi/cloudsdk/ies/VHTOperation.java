/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.cloudsdk.ies;

import java.util.Arrays;
import java.util.Objects;

import org.apache.commons.codec.binary.Base64;

/**
 * Very High Throughput (VHT) Operation Element, which is potentially present in
 * wifiscan entries. Introduced in 802.11ac (2013).
 */
public class VHTOperation {

	/**
	 * This field is 0 if the channel width is 20 MHz or 40 MHz, and 1 otherwise.
	 * Values of 2 and 3 are deprecated.
	 */
	public final byte channelWidth;
	/**
	 * If the channel is 20 MHz, 40 MHz, or 80 MHz wide, this parameter is the
	 * channel number. E.g., the channel centered at 5180 MHz is channel 36. For a
	 * 160 MHz wide channel, this parameter is the channel number of the 80MHz
	 * channel that contains the primary channel. For a 80+80 MHz wide channel, this
	 * parameter is the channel number of the primary channel.
	 * <p>
	 * This field is an unsigned byte in the specification (i.e., with values
	 * between 0 and 255). But because Java only supports signed bytes, a short
	 * data type is used to store the value.
	 */
	public final short channel1;
	/**
	 * This should be zero unless the channel is 160MHz or 80+80 MHz wide. If the
	 * channel is 160 MHz wide, this parameter is the channel number of the 160 MHz
	 * wide channel. If the channel is 80+80 MHz wide, this parameter is the channel
	 * index of the secondary 80 MHz wide channel.
	 * <p>
	 * This field is an unsigned byte in the specification (i.e., with values
	 * between 0 and 255). But because Java only supports signed bytes, a short
	 * data type is used to store the value.
	 */
	public final short channel2;
	/**
	 * An 8-element array where each element is between 0 and 4 inclusive. MCS means
	 * Modulation and Coding Scheme. NSS means Number of Spatial Streams. There can
	 * be 1, 2, ..., or 8 spatial streams. For each NSS, the corresponding element
	 * in the array should specify which MCSs are supported for that NSS in the
	 * following manner: 0 indicates support for VHT-MCS 0-7, 1 indicates support
	 * for VHT-MCS 0-8, 2 indicates support for VHT-MCS 0-9, and 3 indicates that no
	 * VHT-MCS is supported for that NSS. For the specifics of what each VHT-MCS is,
	 * see IEEE 802.11-2020, Table "21-29" through Table "21-60".
	 */
	public final byte[] vhtMcsForNss;

	/**
	 * Constructs a {@code VHTOperationElement} by decoding {@code vhtOper}.
	 *
	 * @param vhtOper a base64 encoded properly formatted VHT operation element (see
	 *                802.11 standard)
	 */
	public VHTOperation(String vhtOper) {
		byte[] bytes = Base64.decodeBase64(vhtOper);
		this.channelWidth = bytes[0];
		this.channel1 = (short) (bytes[1] & 0xff); // read as unsigned value
		this.channel2 = (short) (bytes[2] & 0xff); // read as unsigned value
		byte[] vhtMcsForNss = new byte[8];
		vhtMcsForNss[0] = (byte) (bytes[3] >>> 6);
		vhtMcsForNss[1] = (byte) ((bytes[3] & 0b00110000) >>> 4);
		vhtMcsForNss[2] = (byte) ((bytes[3] & 0b00001100) >>> 2);
		vhtMcsForNss[3] = (byte) (bytes[3] & 0b00000011);
		vhtMcsForNss[4] = (byte) (bytes[4] >>> 6);
		vhtMcsForNss[5] = (byte) ((bytes[4] & 0b00110000) >>> 4);
		vhtMcsForNss[6] = (byte) ((bytes[4] & 0b00001100) >>> 2);
		vhtMcsForNss[7] = (byte) (bytes[4] & 0b00000011);
		this.vhtMcsForNss = vhtMcsForNss;
	}

	/**
	 * Constructs an {@code HTOperationElement} using the given field values. See
	 * 802.11 for more details.
	 * <p>
	 * For details about the parameters, see the javadocs for the corresponding
	 * member variables.
	 */
	public VHTOperation(
		byte channelWidth,
		short channel1,
		short channel2,
		byte[] vhtMcsForNss
	) {
		/*
		 * XXX some combinations of channelWidth, channel, channel2, and vhtMcsAtNss are
		 * invalid, but this is not checked here. If fidelity to 802.11 is required, the
		 * caller of this method must make sure to pass in valid parameters.
		 */
		this.channelWidth = channelWidth;
		this.channel1 = channel1;
		this.channel2 = channel2;
		this.vhtMcsForNss = vhtMcsForNss;
	}

	/**
	 * Determine whether {@code this} and {@code other} "match" for the purpose of
	 * aggregating statistics.
	 *
	 * @param other another VHT operation element
	 * @return true if the the operation elements "match" for the purpose of
	 *         aggregating statistics; false otherwise.
	 */
	public boolean matchesForAggregation(VHTOperation other) {
		// check everything except vhtMcsForNss
		return other != null && channel1 == other.channel1 &&
			channel2 == other.channel2 && channelWidth == other.channelWidth;
	}

	/**
	 * Determines whether two VHT operation elements should have their statistics
	 * aggregated.
	 *
	 * @param vhtOper1 a base64 encoded properly formatted VHT operation element
	 *                 (see 802.11 standard)
	 *
	 * @param vhtOper2 a base64 encoded properly formatted VHT operation element
	 *                 (see 802.11 standard)
	 * @return true if the two inputs should have their statistics aggregated; false
	 *         otherwise.
	 */
	public static boolean matchesVhtForAggregation(
		String vhtOper1,
		String vhtOper2
	) {
		if (Objects.equals(vhtOper1, vhtOper2)) {
			return true; // true if both are null or they are equal
		}
		if (vhtOper1 == null || vhtOper2 == null) {
			return false; // false if exactly one is null
		}
		VHTOperation vhtOperObj1 = new VHTOperation(vhtOper1);
		VHTOperation vhtOperObj2 = new VHTOperation(vhtOper2);
		return vhtOperObj1.matchesForAggregation(vhtOperObj2);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(vhtMcsForNss);
		result =
			prime * result + Objects.hash(channel1, channel2, channelWidth);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		VHTOperation other = (VHTOperation) obj;
		return channel1 == other.channel1 && channel2 == other.channel2 &&
			channelWidth == other.channelWidth &&
			Arrays.equals(vhtMcsForNss, other.vhtMcsForNss);
	}
}