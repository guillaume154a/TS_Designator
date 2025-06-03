/*
Copyright 2020, Rein (TNO MSG)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package nl.tno.msg.tc_designator;

import de.fraunhofer.iosb.tc_lib.AbstractTestCase;
import de.fraunhofer.iosb.tc_lib.IVCT_BaseModel;
import de.fraunhofer.iosb.tc_lib.IVCT_LoggingFederateAmbassador;
import de.fraunhofer.iosb.tc_lib.IVCT_RTI_Factory;
import de.fraunhofer.iosb.tc_lib.IVCT_RTIambassador;
import de.fraunhofer.iosb.tc_lib.TcFailed;
import de.fraunhofer.iosb.tc_lib.TcInconclusive;
import nl.tno.msg.tc_lib_designator.DesignatorBaseModel;
import nl.tno.msg.tc_lib_designator.DesignatorTcParam;
import nl.tno.msg.tc_lib_designator.DesignatorBaseModel.TestItem;
import nl.tno.msg.tc_lib_designator.DesignatorBaseModel.TestItemIds;
import nl.tno.msg.tc_lib_designator.DesignatorBaseModel.TestItemType;
import hla.rti1516e.FederateHandle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import org.slf4j.Logger;

/**
 * @author Rein (TNO MSG)
 */
public class TC_BaseClass extends AbstractTestCase {
	protected String                            federateName = "IVCT";
	FederateHandle                              federateHandle;

	// Build test case parameters to use
	static DesignatorTcParam                    designatorTcParam;

	// Get logging-IVCT-RTI using tc_param federation name, host
	protected static IVCT_RTIambassador         ivct_rti;
	static DesignatorBaseModel                  designatorBaseModel;

	static IVCT_LoggingFederateAmbassador       ivct_LoggingFederateAmbassador;

	@Override
	public IVCT_BaseModel getIVCT_BaseModel(final String tcParamJson, final Logger logger) throws TcInconclusive {
		try {
			designatorTcParam = new DesignatorTcParam(tcParamJson);
			ivct_rti = IVCT_RTI_Factory.getIVCT_RTI(logger);
			designatorBaseModel = new DesignatorBaseModel(logger, ivct_rti, designatorTcParam);
			ivct_LoggingFederateAmbassador = new IVCT_LoggingFederateAmbassador(designatorBaseModel, logger);
		} catch (TcInconclusive e) {
			throw e;
		} catch (Throwable e) {
			logInternalAndThrowInconclusive(logger, e);
		}
		return designatorBaseModel;
	}

	@Override
	protected void logTestPurpose(final Logger logger) {
		final StringBuilder stringBuilder = new StringBuilder();
		stringBuilder.append("Test purpose to be implemented\n");
		final String testPurpose = stringBuilder.toString();
		logger.info(testPurpose);
	}

	public void displayOperatorInstructions(final Logger logger) throws TcInconclusive {
		String s = new String();
		s = "\n"
				+   "---------------------------------------------------------------------\n"
				+   "OPERATOR INSTRUCTIONS: \n"
				+   "Start the SuT (System under Test) and then hit confirm button\n"
				+   "---------------------------------------------------------------------\n";

		logger.info(s);
		sendOperatorRequest(s);
	}

	@Override
	protected void preambleAction(final Logger logger) throws TcInconclusive {
		try {
			// Notify the operator
			displayOperatorInstructions(logger);

			// Initiate rti
			this.federateHandle = designatorBaseModel.initiateRti(this.federateName, ivct_LoggingFederateAmbassador);

			// Do the necessary calls to get handles and do publish and subscribe
			designatorBaseModel.init(designatorTcParam);
		} catch (TcInconclusive e) {
			throw e;
		} catch (Throwable e) {
			logInternalAndThrowInconclusive(logger, e);
		}
	}

	private void logInternalAndThrowInconclusive(final Logger logger, Throwable e) throws TcInconclusive {
		StringWriter stringWriter = new StringWriter();
		PrintWriter printWriter = new PrintWriter(stringWriter);
		printWriter.print("INTERNAL ERROR - ");
		printWriter.println(e.toString());
		e.printStackTrace(printWriter);
		String exceptionDetails = stringWriter.toString();
		logger.error(exceptionDetails);
		throw new TcInconclusive(exceptionDetails, e);
	}

	@Override
	protected void performTest(final Logger logger) throws TcInconclusive, TcFailed {
		String msg = "function performTest should be overridden";
		logger.warn("INCONCLUSIVE - " + msg);
		throw new TcInconclusive(msg);
	}

	@Override
	protected void postambleAction(final Logger logger) throws TcInconclusive {
		designatorBaseModel.terminateRti();
	}

	protected void handleResultsOfAllEnabledTests(final Logger logger) throws TcInconclusive, TcFailed {
		logger.info("");
		logger.info("Summary of warnings and/or errors:");
		for (final boolean logOnly : new boolean[] { true, false }) {
			int nrOfWarningsOrErrors = 0;
			for (Map.Entry<TestItemIds, TestItem> entry : designatorBaseModel.testItems.getAll().entrySet()) {
				TestItem testItem = entry.getValue();
				if (testItem.getEnabled()) {
					if (!testItem.getResultOk()) {
						nrOfWarningsOrErrors++;
						if (testItem.getType() == TestItemType.Warning) {
							if (logOnly)
								logger.info("------- WARNING - " + testItem.getLogMsg());
						} else if (testItem.getType() == TestItemType.Inconclusive) {
							if (logOnly)
								logger.info("------- INCONCLUSIVE - " + testItem.getLogMsg());
							else
								throw new TcInconclusive(testItem.getLogMsg());
						} else if (testItem.getType() == TestItemType.Fail) {
							if (logOnly)
								logger.info("------- FAIL - " + testItem.getLogMsg());
							else
								throw new TcFailed(testItem.getLogMsg());
						} else {
							String msg = String.format("INTERNAL ERROR - testItemType %d unknown", testItem.getType());
							if (logOnly)
								logger.warn("------- " + msg);
							else
								throw new TcInconclusive(msg);
						}
					}
				}
			}
			if (logOnly) {
				if (nrOfWarningsOrErrors <= 0)
					logger.info("------- None");
				logger.info("");
			}
		}
	}

	protected String secondsToHMS(long s) {
		long h = s / 3600;
		s -= h * 3600;
		long m = s / 60;
		s -= m * 60;
		return String.format("%02d:%02d:%02d", h, m, s);
	}

	protected void runDesignatorBaseModelAndEvaluateTests(final Logger logger) throws TcInconclusive, TcFailed {
		// Allow time to work and get some reflect values
		designatorBaseModel.sleepFor((long)(1000.0 * designatorTcParam.getInitialWaitingTime()));

		// Loop a number of cycles and let designatorBaseModel evaluate the enabled tests
		double sleepTimePerCycle = 1.0; // designatorBaseModel.doTimedTests will be called approximately once per second
		int nrOfCycles = (int) (designatorTcParam.getTotalTestCaseTime() / sleepTimePerCycle);
		logger.info("total test time: " + secondsToHMS(Math.round(designatorTcParam.getTotalTestCaseTime())));
		for (int i = 1; i <= nrOfCycles; i++) {
			designatorBaseModel.sleepFor((long)sleepTimePerCycle * 1000);
			designatorBaseModel.timerCallback();
			sendTcStatus("running", i * 100 / nrOfCycles);
			long remainingSeconds = Math.round((nrOfCycles - i) * sleepTimePerCycle);
			if (i == nrOfCycles || remainingSeconds % 10 == 0)
				logger.info("remaining test time: " + secondsToHMS(remainingSeconds));
		}
		sendTcStatus("running", 100);

		// Loop over enabled test items; log and/or throw if necessary
		handleResultsOfAllEnabledTests(logger);
	}
}
