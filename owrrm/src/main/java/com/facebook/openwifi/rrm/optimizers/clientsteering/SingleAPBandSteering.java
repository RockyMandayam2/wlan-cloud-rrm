/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.optimizers.clientsteering;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.openwifi.cloudsdk.UCentralConstants;
import com.facebook.openwifi.cloudsdk.models.ap.Capabilities;
import com.facebook.openwifi.cloudsdk.models.ap.State;
import com.facebook.openwifi.rrm.DeviceDataManager;
import com.facebook.openwifi.rrm.modules.Modeler.DataModel;
import com.facebook.openwifi.rrm.modules.ModelerUtils;
import com.google.gson.Gson;

/**
 * Implements simple band steering for each AP separately
 * <p>
 * 2G clients below a specified RSSI threshold are deauthenticated. 2G clients
 * above a specified RSSI threshold are asked to move to either 5G or 6G. 5G and
 * 6G clients below a configurable RSSI threshold are asked to move to 2G.
 */
public class SingleAPBandSteering extends ClientSteeringOptimizer {

	/** The Gson instance. */
	private static final Gson gson = new Gson();

	private static final Logger logger =
		LoggerFactory.getLogger(SingleAPBandSteering.class);

	/** The RRM algorithm ID. */
	public static final String ALGORITHM_ID = "band";

	/**
	 * RSSI (dBm) below which a client on 2G should be disconnected using
	 * deauthentication.
	 */
	public static final short DEFAULT_MIN_RSSI_2G = -87;
	/**
	 * RSSI (dBm) above which a client on 2G should be requested to move to
	 * 5G/6G
	 */
	public static final short DEFAULT_MAX_RSSI_2G = -67;
	/**
	 * RSSI (dBm) below which a client on 5G/6G should be requested to move to
	 * 2G
	 */
	public static final short DEFAULT_MIN_RSSI_NON_2G = -82;
	/** Default backoff time (ns) for all APs and radios */
	public static final long DEFAULT_BACKOFF_TIME_NS = 300_000_000_000L; // 5 min

	/** RSSI below which 2G clients are deauthenticated */
	private final short minRssi2G;
	/** RSSI above which 2G clients are asked to move to 5G or 6G */
	private final short maxRssi2G;
	/** RSSI below which 5G and 6G clients are asked to move to 2G */
	private final short minRssiNon2G;
	/** Backoff time (ns) for all APs and radios */
	private final long backoffTimeNs;

	/** Make a SingleAPBandSteering object with the given arguments */
	public static SingleAPBandSteering makeWithArgs(
		DataModel model,
		String zone,
		DeviceDataManager deviceDataManager,
		ClientSteeringState clientSteeringState,
		Map<String, String> args
	) {
		short minRssi2G = DEFAULT_MIN_RSSI_2G;
		short maxRssi2G = DEFAULT_MAX_RSSI_2G;
		short minRssiNon2G = DEFAULT_MIN_RSSI_NON_2G;
		long backoffTimeNs = DEFAULT_BACKOFF_TIME_NS;

		String arg;
		if ((arg = args.get("minRssi2G")) != null) {
			minRssi2G = Short.parseShort(arg);
		}
		if ((arg = args.get("maxRssi2G")) != null) {
			maxRssi2G = Short.parseShort(arg);
		}
		if ((arg = args.get("minRssiNon2G")) != null) {
			minRssiNon2G = Short.parseShort(arg);
		}
		if ((arg = args.get("backoffTimeNs")) != null) {
			backoffTimeNs = Short.parseShort(arg);
		}

		return new SingleAPBandSteering(
			model,
			zone,
			deviceDataManager,
			clientSteeringState,
			minRssi2G,
			maxRssi2G,
			minRssiNon2G,
			backoffTimeNs
		);
	}

	/** Constructor */
	public SingleAPBandSteering(
		DataModel model,
		String zone,
		DeviceDataManager deviceDataManager,
		ClientSteeringState clientSteeringState,
		short minRssi2G,
		short maxRssi2G,
		short minRssiNon2G,
		long backoffTimeNs
	) {
		super(model, zone, deviceDataManager, clientSteeringState);
		this.minRssi2G = minRssi2G;
		this.maxRssi2G = maxRssi2G;
		this.minRssiNon2G = minRssiNon2G;
		this.backoffTimeNs = backoffTimeNs;
	}

	@Override
	public Map<String, Map<String, String>> computeApClientActionMap(
		boolean dryRun
	) {
		Map<String, Map<String, String>> apClientActionMap = new HashMap<>();
		// iterate through every AP
		for (
			Map.Entry<String, List<State>> entry : model.latestStates
				.entrySet()
		) {
			// get the latest state
			// TODO window size (look at multiple states)
			// TODO window percent (% of samples that must violate thresholds)
			List<State> states = entry.getValue();
			if (states == null || states.isEmpty()) {
				continue;
			}
			final String serialNumber = entry.getKey();
			final State state = states.get(states.size() - 1);
			// iterate through every radio and every connected client
			if (state.interfaces == null || state.interfaces.length == 0) {
				continue;
			}
			final long currentTimeNs = System.nanoTime();
			for (State.Interface iface : state.interfaces) {
				if (iface.ssids == null || iface.ssids.length == 0) {
					continue;
				}
				for (State.Interface.SSID ssid : iface.ssids) {
					if (
						ssid.associations == null ||
							ssid.associations.length == 0
					) {
						continue;
					}
					final State.Radio radio = gson.fromJson(
						ssid.radio,
						State.Radio.class
					);
					// get band for this radio/ssid
					Map<String, Capabilities.Phy> capabilitiesPhy =
						model.latestDeviceCapabilitiesPhy
							.get(serialNumber);
					if (capabilitiesPhy == null) {
						continue;
					}
					final String band = ModelerUtils.getBand(
						radio,
						capabilitiesPhy
					);
					if (band == null) {
						continue;
					}
					// decide steering action (if any) for each client
					for (
						State.Interface.SSID.Association assoc : ssid.associations
					) {
						maybeAddApClientActionEntry(
							assoc,
							band,
							serialNumber,
							currentTimeNs,
							apClientActionMap,
							dryRun
						);
					}
				}
			}
		}
		return apClientActionMap;
	}

	/**
	 * If a client steering action is desired, add an appropriate entry to the
	 * apClientActionMap, unless this run is marked as a dry run.
	 *
	 * @param assoc association between AP radio and client
	 * @param band band (e.g., "2G")
	 * @param serialNumber AP serial number
	 * @param currentTimeNs JVM monotonic time in ns
	 * @param dryRun if set, do not apply changes
	 * @param apClientActionMap map from AP serial number to client MAC to client steering action name ({@link ClientSteeringOptimizer.CLIENT_STEERING_ACTIONS#name()})
	 */
	private void maybeAddApClientActionEntry(
		State.Interface.SSID.Association assoc,
		String band,
		String serialNumber,
		long currentTimeNs,
		Map<String, Map<String, String>> apClientActionMap,
		boolean dryRun
	) {
		// decide whether to do any band steering
		// TODO check which bands AP & client can use (see 11k)
		if (UCentralConstants.BAND_2G.equals(band)) {
			if (assoc.rssi < minRssi2G) {
				if (
					clientSteeringState
						.registerIfBackoffExpired(
							serialNumber,
							assoc.station,
							currentTimeNs,
							backoffTimeNs,
							dryRun
						)
				) {
					logger.debug(
						"Planning to deauthenticate client {} on AP {}",
						assoc.station,
						serialNumber
					);
					apClientActionMap
						.computeIfAbsent(
							serialNumber,
							k -> new HashMap<>()
						)
						.put(
							assoc.station,
							CLIENT_STEERING_ACTIONS.DEAUTHENTICATE
								.name()
						);
				}
			} else if (assoc.rssi > maxRssi2G) {
				if (
					clientSteeringState
						.registerIfBackoffExpired(
							serialNumber,
							assoc.station,
							currentTimeNs,
							backoffTimeNs,
							dryRun
						)
				) {
					logger.debug(
						"Planning to request client {} on AP {} to move to 5G or 6G",
						assoc.station,
						serialNumber
					);
					apClientActionMap
						.computeIfAbsent(
							serialNumber,
							k -> new HashMap<>()
						)
						.put(
							assoc.station,
							CLIENT_STEERING_ACTIONS.STEER_UP
								.name()
						);
				}
			}
			// otherwise, do nothing
		} else {
			// treat 5G and 6G clients the same way
			if (assoc.rssi < minRssiNon2G) {
				if (
					clientSteeringState
						.registerIfBackoffExpired(
							serialNumber,
							assoc.station,
							currentTimeNs,
							backoffTimeNs,
							dryRun
						)
				) {
					logger.debug(
						"Planning to request client {} on AP {} to move to 2G",
						assoc.station,
						serialNumber
					);
					apClientActionMap
						.computeIfAbsent(
							serialNumber,
							k -> new HashMap<>()
						)
						.put(
							assoc.station,
							CLIENT_STEERING_ACTIONS.STEER_DOWN
								.name()
						);
				}
			}
			// otherwise, do nothing
		}
	}
}
