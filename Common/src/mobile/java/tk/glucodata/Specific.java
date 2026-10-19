/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Sun Apr 16 20:57:54 CEST 2023                                                 */


package tk.glucodata;

import android.content.IntentFilter;

public class Specific {
	// Hand the shared code this variant's implementations before anything can draw an
	// arrow, evaluate a reading or broadcast a rate. Explicit registration, not a runtime
	// name lookup: R8 renames these in release builds (see TrendAccess/CustomAlertAccess).
	// Called from Applic.onCreate(), NOT from start(): start() runs at the end of
	// initproc(), behind numio.setlibrary() and the sensor restore, and after a reboot
	// the reading pipeline (boot receiver, service restart) can evaluate a value before
	// initproc() gets that far -- the forecast alerts then fired on the two-point
	// fallback slope. Registration stores singletons and needs nothing from initproc.
	static void registerBridges() {
		TrendAccess.register(tk.glucodata.logic.TrendEngineVelocityProvider.INSTANCE);
		CustomAlertAccess.register(tk.glucodata.logic.CustomAlertManagerController.INSTANCE);
		JournalAccess.register(tk.glucodata.data.journal.WearJournalBridge.INSTANCE);
		JournalTreatmentUploadAccess.register(tk.glucodata.data.journal.JournalTreatmentUploader.INSTANCE);
		NightscoutTreatmentImportAccess.register(tk.glucodata.data.journal.NightscoutJournalFollowerImporter.INSTANCE);
		tk.glucodata.ui.ComposeHostAccess.register(tk.glucodata.ui.MobileComposeHost.INSTANCE);
		JournalSnapshotAccess.register(tk.glucodata.OutboundApiJournalSnapshot.INSTANCE);
		NotificationPredictionAccess.register(tk.glucodata.NotificationPredictionOverlay.INSTANCE);
		GlucoseUncertaintyAccess.register(tk.glucodata.data.GlucoseUncertaintyStore.INSTANCE);
		CalibrationProfileAccess.register(tk.glucodata.data.calibration.MobileCalibrationProfileBridge.INSTANCE);
		CalibrationAccess.register(tk.glucodata.data.calibration.MobileCalibrationProvider.INSTANCE);
		tk.glucodata.ui.AlarmActivityAccess.register(tk.glucodata.ui.MobileAlarmActivityHost.INSTANCE);
		if (!SetColorsScreenAccess.isRegistered())
			SetColorsScreenAccess.register(new tk.glucodata.settings.MobileSetColors());
		GlucoseAlarmsAccess.register(new tk.glucodata.GlucoseAlarmsAccess.Factory() {
		    @Override
		    public tk.glucodata.GlucoseAlarmHandler create(android.app.Application application) {
		        return new tk.glucodata.MobileGlucoseAlarms(application);
		    }
		});
		LibreviewJournalEntriesAccess.register(tk.glucodata.data.journal.LibreviewJournalEntries.INSTANCE);
		HistoryRepositoryAccess.register(tk.glucodata.data.MobileHistoryRepositoryBridge.INSTANCE);
		HistorySyncBridgeAccess.register(tk.glucodata.data.MobileHistorySyncBridge.INSTANCE);
		CloneRecoveryAccessBridge.register(tk.glucodata.data.MobileCloneRecoveryBridge.INSTANCE);
		CloneOutgoingRecoveryAccessBridge.register(tk.glucodata.data.MobileCloneOutgoingRecoveryBridge.INSTANCE);
		LegacyScreensAccess.register(tk.glucodata.settings.MobileLegacyScreens.INSTANCE);
		BluetoothMeterAccess.register(tk.glucodata.MobileBluetoothMeters.INSTANCE);
		NovoPenAccess.register(tk.glucodata.MobileNovoPenScan.INSTANCE);
		LibreNumbersAccess.register(tk.glucodata.settings.MobileLibreNumbersLayout.INSTANCE);
		HealthConnectAccess.register(tk.glucodata.MobileHealthConnect.INSTANCE);
		HealthPermissionsAccess.register(tk.glucodata.MobileHealthPermissions.INSTANCE);
		// start() calls this again as a safety net, so anything built here must be built
		// once: a second AllData would split the ConnectIQ state between two hubs.
		if (!FloatingConfigScreenAccess.isRegistered())
			FloatingConfigScreenAccess.register(new tk.glucodata.MobileFloatingConfig());
		if (!NumberDataAccess.isRegistered())
			NumberDataAccess.register(new tk.glucodata.nums.AllData());
		VariantBootstrapAccess.register(new VariantBootstrapAccess.Factory() {
			@Override
			public VariantBootstrap create() {
				return new MobileVariantBootstrap();
			}
		});
	}
}
