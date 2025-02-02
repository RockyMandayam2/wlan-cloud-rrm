/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.openwifi.cloudsdk.UCentralApConfiguration;
import com.facebook.openwifi.cloudsdk.UCentralClient;
import com.facebook.openwifi.cloudsdk.UCentralUtils;
import com.facebook.openwifi.cloudsdk.WifiScanEntry;
import com.facebook.openwifi.cloudsdk.kafka.UCentralKafkaConsumer;
import com.facebook.openwifi.cloudsdk.kafka.UCentralKafkaConsumer.KafkaRecord;
import com.facebook.openwifi.cloudsdk.models.ap.Capabilities;
import com.facebook.openwifi.cloudsdk.models.ap.State;
import com.facebook.openwifi.cloudsdk.models.gw.DeviceCapabilities;
import com.facebook.openwifi.cloudsdk.models.gw.DeviceWithStatus;
import com.facebook.openwifi.cloudsdk.models.gw.ServiceEvent;
import com.facebook.openwifi.cloudsdk.models.gw.StatisticsRecords;
import com.facebook.openwifi.rrm.DeviceConfig;
import com.facebook.openwifi.rrm.DeviceDataManager;
import com.facebook.openwifi.rrm.RRMConfig.ModuleConfig.ModelerParams;
import com.facebook.openwifi.rrm.Utils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

/**
 * Modeler module.
 */
public class Modeler implements Runnable {
	private static final Logger logger = LoggerFactory.getLogger(Modeler.class);

	/** The module parameters. */
	private final ModelerParams params;

	/** The device data manager. */
	private final DeviceDataManager deviceDataManager;

	/** The uCentral client instance. */
	private final UCentralClient client;

	/** Kafka input data types. */
	public enum InputDataType { STATE, WIFISCAN }

	/** Kafka input data wrapper. */
	private class InputData {
		/** Data type. */
		public final InputDataType type;

		/** Records. */
		public final List<KafkaRecord> records;

		/** Constructor. */
		public InputData(InputDataType type, List<KafkaRecord> records) {
			this.type = type;
			this.records = records;
		}
	}

	/** The blocking data queue. */
	private final BlockingQueue<InputData> dataQueue =
		new LinkedBlockingQueue<>();

	/** Data model representation. */
	public static class DataModel {
		// TODO: This is only a placeholder implementation.
		// At minimum, we may want to aggregate recent wifi scan responses and
		// keep a rolling average for stats.

		/**
		 * The "result" of a wifiscan can include multiple responses. This maps from an
		 * AP (serial number) to a list of most recent wifiscan "results" where each
		 * "result" itself is a list of responses from other APs.
		 *
		 * @see UCentralClient#wifiScan(String, boolean)
		 */
		public Map<String, List<List<WifiScanEntry>>> latestWifiScans =
			new ConcurrentHashMap<>();

		/** List of latest states per device. */
		public Map<String, List<State>> latestStates =
			new ConcurrentHashMap<>();

		/** List of radio info per device. */
		public Map<String, JsonArray> latestDeviceStatusRadios =
			new ConcurrentHashMap<>();

		/** List of capabilities per device. */
		public Map<String, Map<String, Capabilities.Phy>> latestDeviceCapabilitiesPhy =
			new ConcurrentHashMap<>();
	}

	/** The data model. */
	public DataModel dataModel = new DataModel();

	/** The Gson instance. */
	private final Gson gson = new Gson();

	/** Constructor. */
	public Modeler(
		ModelerParams params,
		DeviceDataManager deviceDataManager,
		UCentralKafkaConsumer consumer,
		UCentralClient client,
		DataCollector dataCollector,
		ConfigManager configManager
	) {
		this.params = params;
		this.deviceDataManager = deviceDataManager;
		this.client = client;

		// Register data hooks
		dataCollector.addDataListener(
			getClass().getSimpleName(),
			new DataCollector.DataListener() {
				@Override
				public void processDeviceCapabilities(
					String serialNumber,
					DeviceCapabilities capabilities
				) {
					updateDeviceCapabilities(serialNumber, capabilities);
				}
			}
		);

		// Register config hooks
		configManager.addConfigListener(
			getClass().getSimpleName(),
			new ConfigManager.ConfigListener() {
				@Override
				public void receiveDeviceConfig(
					String serialNumber,
					UCentralApConfiguration config
				) {
					updateDeviceConfig(serialNumber, config);
				}

				@Override
				public boolean processDeviceConfig(
					String serialNumber,
					UCentralApConfiguration config
				) {
					return false;
				}
			}
		);

		// Register Kafka listener
		if (consumer != null) {
			// We only push data to a blocking queue to be processed by this
			// thread later, instead of the Kafka consumer thread
			consumer.addKafkaListener(
				getClass().getSimpleName(),
				new UCentralKafkaConsumer.KafkaListener() {
					// NOTE: list copying due to potential modification in run()

					@Override
					public void handleStateRecords(List<KafkaRecord> records) {
						dataQueue.offer(
							new InputData(
								InputDataType.STATE,
								new ArrayList<>(records)
							)
						);
					}

					@Override
					public void handleWifiScanRecords(
						List<KafkaRecord> records
					) {
						dataQueue.offer(
							new InputData(
								InputDataType.WIFISCAN,
								new ArrayList<>(records)
							)
						);
					}

					@Override
					public void handleServiceEventRecords(
						List<ServiceEvent> serviceEventRecords
					) {
						// ignored
					}
				}
			);
		}
	}

	@Override
	public void run() {
		logger.info("Fetching initial data...");
		fetchInitialData();

		// Poll for data until interrupted
		logger.info("Modeler awaiting data...");
		while (!Thread.currentThread().isInterrupted()) {
			try {
				InputData inputData = dataQueue.take();

				// Drop records here if RRM is disabled for a device
				int recordCount = inputData.records.size();
				if (
					inputData.records.removeIf(
						record -> !isRRMEnabled(record.serialNumber)
					)
				) {
					logger.debug(
						"Dropping {} Kafka record(s) for non-RRM-enabled devices",
						recordCount - inputData.records.size()
					);
				}

				processData(inputData);
			} catch (InterruptedException e) {
				logger.error("Interrupted!", e);
				break;
			}
		}
		logger.error("Thread terminated!");
	}

	/** Fetch initial data (called only once). */
	private void fetchInitialData() {
		while (!client.isInitialized()) {
			logger.trace("Waiting for ucentral client");
			try {
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}

		// TODO: backfill data from database?

		// Fetch state from uCentralGw
		List<DeviceWithStatus> devices = client.getDevices();
		if (devices == null) {
			logger.error("Failed to fetch devices!");
			return;
		}
		logger.debug("Received device list of size = {}", devices.size());
		for (DeviceWithStatus device : devices) {
			// Check if enabled
			if (!isRRMEnabled(device.serialNumber)) {
				logger.debug(
					"Skipping data for non-RRM-enabled device {}",
					device.serialNumber
				);
				continue;
			}

			StatisticsRecords records =
				client.getLatestStats(device.serialNumber, 1);
			if (records == null || records.data.size() != 1) {
				continue;
			}
			JsonObject state = records.data.get(0).data;
			if (state != null) {
				try {
					State stateModel = gson.fromJson(state, State.class);
					dataModel.latestStates.computeIfAbsent(
						device.serialNumber,
						k -> new LinkedList<>()
					).add(stateModel);
					logger.debug(
						"Device {}: added initial state from uCentralGw",
						device.serialNumber
					);
				} catch (JsonSyntaxException e) {
					logger.error(
						String.format(
							"Device %s: failed to deserialize state: %s",
							device.serialNumber,
							state
						),
						e
					);
				}
			}
		}
	}

	/** Process input data. */
	private void processData(InputData data) {
		// for logging only
		Set<String> stateUpdates = new TreeSet<>();
		Set<String> wifiScanUpdates = new TreeSet<>();

		switch (data.type) {
		case STATE:
			for (KafkaRecord record : data.records) {
				JsonObject state = record.payload.getAsJsonObject("state");
				if (state != null) {
					try {
						State stateModel = gson.fromJson(state, State.class);
						List<State> latestStatesList = dataModel.latestStates
							.computeIfAbsent(
								record.serialNumber,
								k -> new LinkedList<>()
							);
						while (
							latestStatesList.size() >= params.stateBufferSize
						) {
							latestStatesList.remove(0);
						}
						latestStatesList.add(stateModel);
						stateUpdates.add(record.serialNumber);
					} catch (JsonSyntaxException e) {
						logger.error(
							String.format(
								"Device %s: failed to deserialize state: %s",
								record.serialNumber,
								state
							),
							e
						);
					}
				}
			}
			break;
		case WIFISCAN:
			for (KafkaRecord record : data.records) {
				List<List<WifiScanEntry>> wifiScanList =
					dataModel.latestWifiScans.computeIfAbsent(
						record.serialNumber,
						k -> new LinkedList<>()
					);

				// Parse and validate this record
				List<WifiScanEntry> scanEntries = UCentralUtils
					.parseWifiScanEntries(record.payload, record.timestampMs);
				if (scanEntries == null) {
					continue;
				}

				// Add to list (and truncate to max size)
				while (wifiScanList.size() >= params.wifiScanBufferSize) {
					wifiScanList.remove(0);
				}
				wifiScanList.add(scanEntries);
				wifiScanUpdates.add(record.serialNumber);
			}
			break;
		}

		if (!stateUpdates.isEmpty()) {
			logger.debug(
				"Received state updates for {} device(s): [{}]",
				stateUpdates.size(),
				String.join(", ", stateUpdates)
			);
		}
		if (!wifiScanUpdates.isEmpty()) {
			logger.debug(
				"Received wifi scan results for {} device(s): [{}]",
				wifiScanUpdates.size(),
				String.join(", ", wifiScanUpdates)
			);
		}
	}

	/**
	 * Update device capabilities into DataModel whenever there are new changes.
	 */
	private void updateDeviceCapabilities(
		String serialNumber,
		DeviceCapabilities capabilities
	) {
		dataModel.latestDeviceCapabilitiesPhy.put(
			serialNumber,
			capabilities.capabilities.wifi
		);
	}

	/**
	 * Update device config into DataModel whenever there are new changes.
	 */
	private void updateDeviceConfig(
		String serialNumber,
		UCentralApConfiguration config
	) {
		// Get old vs new radios info and store the new radios info
		JsonArray newRadioList = config.getRadioConfigList();
		Set<String> newRadioBandsSet = config.getRadioBandsSet(newRadioList);
		JsonArray oldRadioList = dataModel.latestDeviceStatusRadios
			.put(serialNumber, newRadioList);
		Set<String> oldRadioBandsSet = config.getRadioBandsSet(oldRadioList);

		// Print info only when there are any updates
		if (!oldRadioBandsSet.equals(newRadioBandsSet)) {
			logger.info(
				"Device {}: the new radios list is: {} (was {}).",
				serialNumber,
				newRadioBandsSet.toString(),
				oldRadioBandsSet.toString()
			);
		}
	}

	/** Return whether the given device has RRM enabled. */
	private boolean isRRMEnabled(String serialNumber) {
		DeviceConfig deviceConfig =
			deviceDataManager.getDeviceConfig(serialNumber);
		if (deviceConfig == null) {
			return false;
		}
		return deviceConfig.enableRRM;
	}

	/** Return the current data model (direct reference). */
	public DataModel getDataModel() {
		return dataModel;
	}

	/** Return the current data model (deep copy). */
	public DataModel getDataModelCopy() {
		return Utils.deepCopy(dataModel, DataModel.class);
	}

	/** Revalidate the data model to remove any non-RRM-enabled devices. */
	public void revalidate() {
		if (
			dataModel.latestWifiScans.entrySet()
				.removeIf(e -> !isRRMEnabled(e.getKey()))
		) {
			logger.debug("Removed some wifi scan entries from data model");
		}
		if (
			dataModel.latestStates.entrySet()
				.removeIf(e -> !isRRMEnabled(e.getKey()))
		) {
			logger.debug("Removed some state entries from data model");
		}
		if (
			dataModel.latestDeviceStatusRadios.entrySet()
				.removeIf(e -> !isRRMEnabled(e.getKey()))
		) {
			logger.debug("Removed some status entries from data model");
		}
		if (
			dataModel.latestDeviceCapabilitiesPhy.entrySet()
				.removeIf(e -> !isRRMEnabled(e.getKey()))
		) {
			logger.debug("Removed some capabilities entries from data model");
		}
	}
}
