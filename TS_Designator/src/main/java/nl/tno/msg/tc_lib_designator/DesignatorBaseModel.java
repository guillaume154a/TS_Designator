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

package nl.tno.msg.tc_lib_designator;

import de.fraunhofer.iosb.tc_lib.IVCT_BaseModel;
import de.fraunhofer.iosb.tc_lib.IVCT_RTIambassador;
import de.fraunhofer.iosb.tc_lib.TcInconclusive;
import hla.rti1516e.AttributeHandle;
import hla.rti1516e.AttributeHandleValueMap;
import hla.rti1516e.FederateAmbassador;
import hla.rti1516e.FederateHandle;
import hla.rti1516e.LogicalTime;
import hla.rti1516e.MessageRetractionHandle;
import hla.rti1516e.ObjectClassHandle;
import hla.rti1516e.ObjectInstanceHandle;
import hla.rti1516e.OrderType;
import hla.rti1516e.TransportationTypeHandle;
import hla.rti1516e.encoding.EncoderFactory;
import hla.rti1516e.exceptions.FederateInternalError;
import hla.rti1516e.exceptions.RTIinternalError;
import hla.rti1516e.RtiFactoryFactory;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.xml.bind.JAXBException;
import nl.tno.rpr.datatypes.*;
import omt.ObjectModelType;
import omt.helpers.OmtFunctions;
import omtcodec.OMTcodecFactory;
import org.slf4j.Logger;

/**
 * @author Rein (TNO MSG)
 */
public class DesignatorBaseModel extends IVCT_BaseModel {
	private long tNow = -1;
	private DesignatorTcParam designatorTcParam;
	private Logger logger;
	private final Map<ObjectInstanceHandle, KnownObject> knownObjects = new ConcurrentHashMap<ObjectInstanceHandle, KnownObject>();
	private EncoderFactory encoderFactory;
	private OMTcodecFactory omtFactory;

	public enum TestItemIds {
		InternalError,
		DetectedAtLeastOneDesignatorCreation,
		Decoding,
		MandatoryAttributesHaveBeenSet,
		MandatoryWarningOnlyAttributesHaveBeenSet,
		MultipleSettingOfStaticAttribute,
		HostEntityIdRefersToExistingEntity,
		HostObjectNameRefersToExistingEntity,
		HostEntityIdAndHostObjectNameReferToSameEntity,
		DesignatedObjectIdIsEmptyOrRefersToExistingEntity,
		OutputPowerIsNonNegative,
		EmissionWavelengthIsNonNegative,
		RelSpotLocNotSetOrZeroIfNoDesignatedObject,
		NoAttributeUpdatesWhilePowerZero,
		DetectedAtLeastOneDesignatorRemoval,
		PowerZeroBeforeRemoval,
		DoNotStayLongInPowerZeroStateWarningOnly,
		RemovedQuicklyAfterPowerZeroWarningOnly,
		DesignatorRemovedBeforeHostRemoved,
		DrStaticAccelerationNotSetOrZero,
		DrNonStaticAccelerationSet,
		DrAlgoStaticOrFvw,
		DrUpdateOnHeartbeat,
		DrPositionExtrapolation;
	}

	public enum TestItemType {
		Warning,
		Inconclusive,
		Fail;
	}

	public enum TC {
		TC_Life,
		TC_DR
	}

	public class TestItem {
		private boolean      enabled;   public boolean      getEnabled()   { return enabled;   }   public void enable() { enabled = true; }
		private boolean      resultOk;  public boolean      getResultOk()  { return resultOk;  }
		private TC[]         testCases; public TC[]         getTestCases() { return testCases; }
		private TestItemType type;      public TestItemType getType()      { return type;      }
		private String       logMsg;    public String       getLogMsg()    { return logMsg;    }

		TestItem(boolean enabled, boolean resultOkInitial, TC[] testCases, TestItemType type, String logMsg) {
			this.enabled = false;
			this.resultOk = resultOkInitial;
			this.testCases = testCases;
			this.type = type;
			this.logMsg = logMsg;
		}
	}

	public class TestItems {
		private final Map<TestItemIds, TestItem> items = new LinkedHashMap<TestItemIds, TestItem>(); // LinkedHashMap preserves order

		public TestItems() {
			items.put(TestItemIds.InternalError                                    , new TestItem(true , true , new TC[]{TC.TC_Life, TC.TC_DR}, TestItemType.Inconclusive, "internal error"));
			items.put(TestItemIds.DetectedAtLeastOneDesignatorCreation             , new TestItem(false, false, new TC[]{TC.TC_Life, TC.TC_DR}, TestItemType.Inconclusive, "a Designator shall be created"));
			items.put(TestItemIds.Decoding                                         , new TestItem(false, true , new TC[]{TC.TC_Life, TC.TC_DR}, TestItemType.Fail        , "decoding Designator attribute(s) failed"));
			// IR-DS-0001
			items.put(TestItemIds.MandatoryAttributesHaveBeenSet                   , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "all mandatory Designator attributes shall have a value in the initial attribute value update"));
			// IR-DS-0001
			items.put(TestItemIds.MandatoryWarningOnlyAttributesHaveBeenSet        , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Warning     , "all mandatory (warning only) Designator attributes shall have a value in the initial attribute value update"));
			// IR-DS-0006
			items.put(TestItemIds.MultipleSettingOfStaticAttribute                 , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "static attributes shall be provided only once (unless requested via a Provide Attribute Value Update)"));
			// IR-DS-0003
			items.put(TestItemIds.HostEntityIdRefersToExistingEntity               , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "host entity identifier in Designator shall refer to an existing entity"));
			// IR-DS-0002
			items.put(TestItemIds.HostObjectNameRefersToExistingEntity             , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "host object name in Designator shall refer to an existing entity"));
			// IR-DS-0004
			items.put(TestItemIds.HostEntityIdAndHostObjectNameReferToSameEntity   , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "host entity identifier and host object name in Designator shall refer to same entity"));
			// IR-DS-0005
			items.put(TestItemIds.DesignatedObjectIdIsEmptyOrRefersToExistingEntity, new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "designated object id shall be empty OR shall reference an existing entity"));
			// IR-DS-0007
			items.put(TestItemIds.OutputPowerIsNonNegative                         , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "Designator output power shall not be negative"));
			// IR-DS-0008
			items.put(TestItemIds.EmissionWavelengthIsNonNegative                  , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "Designator emission wavelength shall not be negative"));
			// IR-DS-0010
			items.put(TestItemIds.RelSpotLocNotSetOrZeroIfNoDesignatedObject       , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "when no designated object is provided, RelativeSpotLocation shall be set to zero (or not be set at all)"));
			// IR-DS-0012
			items.put(TestItemIds.NoAttributeUpdatesWhilePowerZero                 , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "Designator attributes shall not be updated while output power is zero"));
			items.put(TestItemIds.DetectedAtLeastOneDesignatorRemoval              , new TestItem(false, false, new TC[]{TC.TC_Life          }, TestItemType.Inconclusive, "a Designator shall be removed"));
			// IR-DS-0014
			items.put(TestItemIds.PowerZeroBeforeRemoval                           , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "output power shall be zero before removal of Designator"));
			// IR-DS-0014
			items.put(TestItemIds.DoNotStayLongInPowerZeroStateWarningOnly         , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Warning     , "Designator shall not stay in output power zero state for a long time"));
			// IR-DS-0014
			items.put(TestItemIds.RemovedQuicklyAfterPowerZeroWarningOnly          , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Warning     , "after setting output power to zero, Designator shall be removed quickly"));
			// IR-DS-0014
			items.put(TestItemIds.DesignatorRemovedBeforeHostRemoved               , new TestItem(false, true , new TC[]{TC.TC_Life          }, TestItemType.Fail        , "Designator shall be removed before/when host entity is removed"));
			// IR-DS-0018
			items.put(TestItemIds.DrStaticAccelerationNotSetOrZero                 , new TestItem(false, true , new TC[]{            TC.TC_DR}, TestItemType.Fail        , "when DR algorithm is set to static, SpotLinearAccelerationVector shall be set to zero (or not be set at all)"));
			// IR-DS-0019
			items.put(TestItemIds.DrNonStaticAccelerationSet                       , new TestItem(false, true , new TC[]{            TC.TC_DR}, TestItemType.Fail        , "when DR algorithm is set to non static, SpotLinearAccelerationVector shall be set"));
			// Test Suite assumption
			items.put(TestItemIds.DrAlgoStaticOrFvw                                , new TestItem(false, true , new TC[]{            TC.TC_DR}, TestItemType.Fail        , "DR algorithm shall be Static or FVW"));
			// IR-DS-0017
			items.put(TestItemIds.DrUpdateOnHeartbeat                              , new TestItem(false, true , new TC[]{            TC.TC_DR}, TestItemType.Fail        , "a spot location update shall be provided at least every <heartbeat> seconds (<heartbeat> defined in TC parameters)"));
			// IR-DS-0016
			items.put(TestItemIds.DrPositionExtrapolation                          , new TestItem(false, true , new TC[]{            TC.TC_DR}, TestItemType.Fail        , "a spot location update shall be provided when the discrepancy between the actual position (as determined by its own internal model) and its dead reckoned position (as determined by using specified dead reckoning algorithm) exceeds a predetermined threshold (defined in TC parameters)"));
		}

		public Map<TestItemIds, TestItem> getAll() {
			return items;
		}

		public TestItem get(TestItemIds testItemId) {
			return items.get(testItemId);
		}
	}
	public TestItems testItems = new TestItems();

	public class KnownObject {
		private final int MaxNrOfEntriesInHistory = 1;
		private Object hlaObject = null;
		public  LinkedList<Object> history = null;
		private Map<Pair<TestItemIds, AttributeHandle>, Boolean> currentOkState = null;
		private boolean alwaysReportWhenTestPassesAfterFail = designatorTcParam.getAlwaysReportWhenTestPassesAfterFail();

		public KnownObject(Object hlaObject) {
			this.hlaObject = hlaObject;
			history = new LinkedList<Object>();
			currentOkState = new ConcurrentHashMap<Pair<TestItemIds, AttributeHandle>, Boolean>();
			if (hlaObject instanceof HlaDesignator) {
				for (Map.Entry<TestItemIds, TestItem> entry : testItems.getAll().entrySet()) {
					TestItemIds testItemId = entry.getKey();
					for (AttributeHandle hAttr : HlaDesignator.getAttrHandleSet()) {
						setCurrentOkState(testItemId, hAttr, true);
					}
				}
			}
		}

		public boolean getCurrentOkState(TestItemIds testItemId, AttributeHandle hAttr) {
			return currentOkState.get(new Pair<TestItemIds, AttributeHandle>(testItemId, hAttr)).booleanValue();
		}

		public void setCurrentOkState(TestItemIds testItemId, AttributeHandle hAttr, boolean okState) {
			currentOkState.put(new Pair<TestItemIds, AttributeHandle>(testItemId, hAttr), new Boolean(okState));
		}

		public boolean getCurrentOkState(TestItemIds testItemId) {
			return getCurrentOkState(testItemId, HlaDesignator.getAttrIdCodeName()); // use some arbitrary fixed attributeHandle when no attributeHandle has been specified
		}

		public void setCurrentOkState(TestItemIds testItemId, boolean okState) {
			setCurrentOkState(testItemId, HlaDesignator.getAttrIdCodeName(), okState); // use some arbitrary fixed attributeHandle when no attributeHandle has been specified
		}

		// Store deep copy of object in history
		public void storeInHistory() {
			if (hlaObject instanceof HlaBaseEntity) {
				history.add(((HlaBaseEntity)hlaObject).newInstance());
			} else if (hlaObject instanceof HlaDesignator) {
				history.add(((HlaDesignator)hlaObject).newInstance());
			}
			while (history.size() > MaxNrOfEntriesInHistory)
				history.removeFirst();
		}

		@SuppressWarnings("unchecked")
		public <T> T get(Class<T> typeKey) {
			if (hlaObject == null)
				return null;
			else if (hlaObject.getClass().equals(typeKey))
				return (T) hlaObject;
			return null;
		}

		public HlaDesignator getPrevDesignator() {
			HlaDesignator prevDesignator = null;
			if (hlaObject instanceof HlaDesignator && history.size() > 0)
				prevDesignator = (HlaDesignator) history.getLast();
			return prevDesignator;
		}
	}

	/**
	 * @param logger
	 * 			reference to a logger
	 * @param ivct_rti
	 *			reference to the RTI ambassador
	 * @param designatorTcParam
	 *			designator ivct parameters
	 */
	public DesignatorBaseModel(final Logger logger, final IVCT_RTIambassador ivct_rti, final DesignatorTcParam designatorTcParam) {
		super(logger, designatorTcParam);
		tNow = System.currentTimeMillis();
		this.logger = logger;
		this.ivct_rti = ivct_rti;
		this.designatorTcParam = designatorTcParam;
		this.encoderFactory = ivct_rti.getEncoderFactory();
	}

	private void logInternalErrorAsWarningAndThrowInconclusive(String errorMsg, Object... args) throws TcInconclusive {
		// String.format doesn't work; so renumber all "{}" in errorMsg, then use MessageFormat.format
		int i = 0;
		while(errorMsg.contains("{}"))
			errorMsg = errorMsg.replaceFirst(Pattern.quote("{}"), "{" + i++ + "}");
		String completeErrorMsg = "INTERNAL ERROR - " + MessageFormat.format(errorMsg, args);
		logger.warn(completeErrorMsg);
		throw new TcInconclusive(completeErrorMsg);
	}

	/**
	 * @param entityId
	 *          the entity id
	 * @return the entity id as a string
	 */
	private String entityIdentifierToString(EntityIdentifierStruct entityId ) {
		if (entityId == null || entityId.getFederateIdentifier() == null) {
			return "";
		}
		return String.format("%1d:%1d:%1d", entityId.getFederateIdentifier().getSiteID(),
				entityId.getFederateIdentifier().getApplicationID(), entityId.getEntityNumber());
	}

	/**
	 * @param sleepTime
	 *          time to sleep
	 * @throws TcInconclusive on error
	 */
	public void sleepFor(final long sleepTime) throws TcInconclusive {
		try {
			Thread.sleep(sleepTime);
		} catch (final InterruptedException e) {
			logInternalErrorAsWarningAndThrowInconclusive("sleepFor problem");
		}
	}

	public void init(DesignatorTcParam designatorTcParam) throws TcInconclusive {
		// Create the encoder factory and the OMT codec factory
		try {
			URL[] urls = designatorTcParam.getUrls();
			ObjectModelType module = OmtFunctions.readOmt(urls[0]);
			ObjectModelType mim = OmtFunctions.readOmt(urls[1]);
			encoderFactory = RtiFactoryFactory.getRtiFactory().getEncoderFactory();
			omtFactory = new OMTcodecFactory(encoderFactory, new ObjectModelType[]{module, mim});
		} catch (IOException | JAXBException | RTIinternalError e) {
			logInternalErrorAsWarningAndThrowInconclusive("Error creating OMT codec factory");
		}

		// Initialize HlaBaseEntity; subscribe to it's attributes; request attribute update
		if (!HlaBaseEntity.init(logger, ivct_rti, encoderFactory, omtFactory))
			logInternalErrorAsWarningAndThrowInconclusive("Error in initializing HlaBaseEntity");
		if (!HlaBaseEntity.subscribe(ivct_rti))
			logInternalErrorAsWarningAndThrowInconclusive("Error in subscribing HlaBaseEntity");
		if (!HlaBaseEntity.requestAttributeValueUpdate(ivct_rti))
			logInternalErrorAsWarningAndThrowInconclusive("Error in request attribute value update for HlaBaseEntity");

		// Initialize HlaDesignator; subscribe to it's attributes; request attribute update
		if (!HlaDesignator.init(logger, ivct_rti, encoderFactory, omtFactory))
			logInternalErrorAsWarningAndThrowInconclusive("Error in initializing HlaDesignator");
		if (!HlaDesignator.subscribe(ivct_rti))
			logInternalErrorAsWarningAndThrowInconclusive("Error in subscribing HlaDesignator");
		if (!HlaDesignator.requestAttributeValueUpdate(ivct_rti))
			logInternalErrorAsWarningAndThrowInconclusive("Error in request attribute value update for HlaDesignator");
	}

	/**
	 * @param entityIdentifier
	 *          an entityIdentifier
	 * @return ObjectInstanceHandle if entity found, otherwise null
	 */
	private ObjectInstanceHandle getObjectByEntityId(EntityIdentifierStruct entityIdentifier) {
		String entityIdString = entityIdentifierToString(entityIdentifier);
		logger.debug("Finding object with entityId: {}", entityIdString);
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			HlaBaseEntity aBaseEntity = knownObjectEntry.getValue().get(HlaBaseEntity.class);
			if (aBaseEntity != null && aBaseEntity.atLeastOneAttributeHasBeenSet()) {
				String aBaseEntityIdString = entityIdentifierToString(aBaseEntity.getEntityIdentifier());
				logger.trace("A known entityId: {}", aBaseEntityIdString);
				if (entityIdString.equals(aBaseEntityIdString)) {
					logger.trace("EntityId refers to an existing BaseEntity");
					return knownObjectEntry.getKey();
				}
			}
		}
		logger.debug("entity with id {} not found", entityIdString);
		return null;
	}

	private HlaBaseEntity getBaseEntityByEntityId(EntityIdentifierStruct entityIdentifier) {
		ObjectInstanceHandle hObject = getObjectByEntityId(entityIdentifier);
		if (hObject != null)
			return knownObjects.get(hObject).get(HlaBaseEntity.class);
		return null;
	}

	/**
	 * @param objectName
	 *          name of entity to be found
	 * @return ObjectInstanceHandle of designated entity if entity found, otherwise null
	 */
	private ObjectInstanceHandle getObjectByObjectName(String objectName) {
		logger.debug("Finding object with object name: {}", objectName);
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			HlaBaseEntity aBaseEntity = knownObjectEntry.getValue().get(HlaBaseEntity.class);
			if (aBaseEntity != null && aBaseEntity.atLeastOneAttributeHasBeenSet()) {
				String anObjectName = aBaseEntity.getObjectName();
				logger.trace("A known object name: {}", anObjectName);
				if (anObjectName.equals(objectName)) {
					logger.trace("Object name refers to an existing BaseEntity");
					return knownObjectEntry.getKey();
				}
			}
		}
		logger.debug("entity with object name {} not found", objectName);
		return null;
	}

	private HlaBaseEntity getBaseEntityByObjectName(String objectName) {
		ObjectInstanceHandle hObject = getObjectByObjectName(objectName);
		if (hObject != null)
			return knownObjects.get(hObject).get(HlaBaseEntity.class);
		return null;
	}

	private WorldLocationStruct computeDeadreckonedSpotLocation(HlaDesignator designator, long currentTime) {
		WorldLocationStruct prevLocation = designator.getDesignatorSpotLocation();
		WorldLocationStruct deadreckonedLocation = new WorldLocationStruct();
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())
				&& designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.DRM_FVW
				&& designator.attrHasBeenSet(HlaDesignator.getAttrIdSpotLinearAccelerationVector())) {
			double timePassed = 1e-3 * (currentTime - designator.getAttrTime(HlaDesignator.getAttrIdDesignatorSpotLocation()));
			AccelerationVectorStruct acceleration = designator.getSpotLinearAccelerationVector();
			deadreckonedLocation.setX(prevLocation.getX() + (0.5 * acceleration.getXAcceleration() * timePassed * timePassed));
			deadreckonedLocation.setY(prevLocation.getY() + (0.5 * acceleration.getYAcceleration() * timePassed * timePassed));
			deadreckonedLocation.setZ(prevLocation.getZ() + (0.5 * acceleration.getZAcceleration() * timePassed * timePassed));
		} else {
			deadreckonedLocation.setX(prevLocation.getX());
			deadreckonedLocation.setY(prevLocation.getY());
			deadreckonedLocation.setZ(prevLocation.getZ());
		}
		return deadreckonedLocation;
	}

	private double distance(WorldLocationStruct pos1, WorldLocationStruct pos2) {
		double dist = Math.sqrt(
				Math.pow(pos1.getX() - pos2.getX(), 2) +
				Math.pow(pos1.getY() - pos2.getY(), 2) +
				Math.pow(pos1.getZ() - pos2.getZ(), 2));
		return dist;
	}

	private void setDetectedAtLeastOneDesignatorCreation() {
		TestItemIds testItemId = TestItemIds.DetectedAtLeastOneDesignatorCreation;
		TestItem testItem = testItems.get(testItemId);
		if (testItem.getEnabled())
			testItem.resultOk = true;
	}

	private void setDetectedAtLeastOneDesignatorRemoval() {
		TestItemIds testItemId = TestItemIds.DetectedAtLeastOneDesignatorRemoval;
		TestItem testItem = testItems.get(testItemId);
		if (testItem.getEnabled())
			testItem.resultOk = true;
	}

	/**
	 * Not ok if a mandatory attribute has not been set
	 */
	private void testMandatoryAttributesHaveBeenSet(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.MandatoryAttributesHaveBeenSet;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean okForAllAttributes = true;
		for (AttributeHandle hAttr : HlaDesignator.getAttrHandleSet()) {
			if (HlaDesignator.getAttrMandatory(hAttr)) {
				boolean ok = designator.attrHasBeenSet(hAttr);
				if (!ok)
					okForAllAttributes = false;
				if (ok != knownObject.getCurrentOkState(testItemId, hAttr)) {
					if (!ok)
						logger.error("Designator {} - mandatory attribute {} has not been set",
								designator.getObjectName(), HlaDesignator.getAttrName(hAttr));
					else if (knownObject.alwaysReportWhenTestPassesAfterFail)
						logger.info("Designator {} - mandatory attribute {} has been set",
								designator.getObjectName(), HlaDesignator.getAttrName(hAttr));
					knownObject.setCurrentOkState(testItemId, hAttr, ok);
				}
			}
		}

		if (!okForAllAttributes)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if a mandatory (warning only) attribute has not been set
	 */
	private void testMandatoryWarningOnlyAttributesHaveBeenSet(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.MandatoryWarningOnlyAttributesHaveBeenSet;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean okForAllAttributes = true;
		for (AttributeHandle hAttr : HlaDesignator.getAttrHandleSet()) {
			if (HlaDesignator.getAttrMandatoryWarningOnly(hAttr)) {
				boolean ok = designator.attrHasBeenSet(hAttr);
				if (!ok)
					okForAllAttributes = false;
				if (ok != knownObject.getCurrentOkState(testItemId, hAttr)) {
					if (!ok)
						logger.error("Designator {} - attribute {} has not been set",
								designator.getObjectName(), HlaDesignator.getAttrName(hAttr));
					else if (knownObject.alwaysReportWhenTestPassesAfterFail)
						logger.info("Designator {} - attribute {} has been set",
								designator.getObjectName(), HlaDesignator.getAttrName(hAttr));
					knownObject.setCurrentOkState(testItemId, hAttr, ok);
				}
			}
		}

		if (!okForAllAttributes)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if a static attribute has been set again
	 */
	private void testMultipleSettingOfStaticAttribute(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.MultipleSettingOfStaticAttribute;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean okForAllAttributes = true;
		HlaDesignator prevDesignator = knownObject.getPrevDesignator();
		if (prevDesignator != null) {
			for (AttributeHandle hAttr : HlaDesignator.getAttrHandleSet()) {
				if (HlaDesignator.getAttrStatic(hAttr)) {
					boolean ok = true;
					if (prevDesignator.attrHasBeenSet(hAttr) && designator.attrHasBeenSet(hAttr)) {
						ok = prevDesignator.getAttrTime(hAttr) == designator.getAttrTime(hAttr);
					}
					if (!ok)
						okForAllAttributes = false;
					if (ok != knownObject.getCurrentOkState(testItemId, hAttr)) {
						if (!ok)
							logger.error("Designator  {} - static attribute {} has been set again, shall only be set once",
									designator.getObjectName(), HlaDesignator.getAttrName(hAttr));
						else if (knownObject.alwaysReportWhenTestPassesAfterFail)
							; // no logging here
						knownObject.setCurrentOkState(testItemId, hAttr, ok);
					}
				}
			}
		}

		if (!okForAllAttributes)
			testItem.resultOk = false;
	}

	/**
	 * Ok if reference is valid
	 */
	private void testHostEntityIdRefersToExistingEntity(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.HostEntityIdRefersToExistingEntity;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdEntityIdentifier())) {
			HlaBaseEntity baseEntity = getBaseEntityByEntityId(designator.getEntityIdentifier());
			ok = baseEntity != null && !baseEntity.isRemoved();
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - entity id {} does not refer to an existing entity",
						designator.getObjectName(), entityIdentifierToString(designator.getEntityIdentifier()));
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - entity id {} does refer to an existing entity",
						designator.getObjectName(), entityIdentifierToString(designator.getEntityIdentifier()));
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if all references valid
	 */
	private void testHostObjectNameRefersToExistingEntity(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.HostObjectNameRefersToExistingEntity;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdHostObjectIdentifier())) {
			HlaBaseEntity baseEntity = getBaseEntityByObjectName(designator.getHostObjectIdentifier());
			ok = baseEntity != null && !baseEntity.isRemoved();
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - host object name {} does not refer to an existing entity",
						designator.getObjectName(), designator.getHostObjectIdentifier());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - host object name {} does refer to an existing entity",
						designator.getObjectName(), designator.getHostObjectIdentifier());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if references valid
	 */
	private void testHostEntityIdAndHostObjectNameReferToSameEntity(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.HostEntityIdAndHostObjectNameReferToSameEntity;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdEntityIdentifier())
				&& designator.attrHasBeenSet(HlaDesignator.getAttrIdHostObjectIdentifier())) {
			ObjectInstanceHandle object1 = getObjectByEntityId(designator.getEntityIdentifier());
			ObjectInstanceHandle object2 = getObjectByObjectName(designator.getHostObjectIdentifier());
			HlaBaseEntity baseEntity1 = getBaseEntityByEntityId(designator.getEntityIdentifier());
			HlaBaseEntity baseEntity2 = getBaseEntityByObjectName(designator.getHostObjectIdentifier());
			ok = object1 != null && object2 != null && object1.equals(object2) && !baseEntity1.isRemoved() && !baseEntity2.isRemoved();
			if (object1 == null || object2 == null)
				ok = true; // if an object is null, another test will already have failed
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - entity identifier and host object name do not refer to same entity",
						designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - entity identifier and host object name do refer to same entity",
						designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if reference valid
	 */
	private void testDesignatedObjectIdIsEmptyOrRefersToExistingEntity(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DesignatedObjectIdIsEmptyOrRefersToExistingEntity;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		String designatedObjectName = "";
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatedObjectIdentifier())) {
			designatedObjectName = designator.getDesignatedObjectIdentifier();
			if (!designatedObjectName.contentEquals("")) {
				HlaBaseEntity designatedBaseEntity = getBaseEntityByObjectName(designatedObjectName);
				ok = designatedBaseEntity != null && !designatedBaseEntity.isRemoved();
			}
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - designated object name {} does not refer to an existing entity",
						designator.getObjectName(), designatedObjectName);
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - designated object name {} does refer to an existing entity",
						designator.getObjectName(), designatedObjectName);
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if power is negative
	 */
	private void testOutputPowerIsNonNegative(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.OutputPowerIsNonNegative;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorOutputPower())) {
			ok = designator.getDesignatorOutputPower() >= 0.0;
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - output power is {}; output power shall not be negative",
						designator.getObjectName(), designator.getDesignatorOutputPower());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - output power is non-negative",
						designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if emission wavelength is negative
	 */
	private void testEmissionWavelengthIsNonNegative(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.EmissionWavelengthIsNonNegative;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorEmissionWavelength())) {
			ok = designator.getDesignatorEmissionWavelength() >= 0.0;
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - emission wavelength is {}; emission wavelength shall not be negative",
						designator.getObjectName(), designator.getDesignatorEmissionWavelength());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - emission wavelength is non-negative",
						designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if relative spot location != zeros while no designated object is provided
	 */
	private void testRelSpotLocNotSetOrZeroIfNoDesignatedObject(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.RelSpotLocNotSetOrZeroIfNoDesignatedObject;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;

		// Check if designated object is provided
		boolean designatedObjectIsProvided = false;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatedObjectIdentifier())) {
			String designatedObjectName = designator.getDesignatedObjectIdentifier();
			if (!designatedObjectName.contentEquals("")) {
				HlaBaseEntity designatedBaseEntity = getBaseEntityByObjectName(designatedObjectName);
				if (designatedBaseEntity != null)
					designatedObjectIsProvided = true;
			}
		}

		// Check if ok
		boolean ok = true;
		if (!designatedObjectIsProvided && designator.attrHasBeenSet(HlaDesignator.getAttrIdRelativeSpotLocation())) {
			RelativePositionStruct relSpotLoc = designator.getRelativeSpotLocation();
			if (relSpotLoc != null) {
				if (relSpotLoc.getBodyXDistance() != 0 || relSpotLoc.getBodyYDistance() != 0 || relSpotLoc.getBodyZDistance() != 0)
					ok = false;
			}
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - when no designated object is provided, RelativeSpotLocation shall be set to zero (or not be set at all)",
						designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - RelativeSpotLocation is set to all zeroes", designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if no attribute updates while power zero
	 */
	private void testNoAttributeUpdatesWhilePowerZero(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.NoAttributeUpdatesWhilePowerZero;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		HlaDesignator prevDesignator = knownObject.getPrevDesignator();
		boolean ok = prevDesignator == null || !prevDesignator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorOutputPower())
				|| prevDesignator.getDesignatorOutputPower() != 0.0;
		if (!knownObject.getCurrentOkState(testItemId))
			ok = false; // this test never passes after it once failed

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - attributes shall not be updated while output power is zero", designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				; // this test never passes after it once failed
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if power set to zero before removal of Designator
	 */
	private void testPowerZeroBeforeRemoval(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.PowerZeroBeforeRemoval;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || !designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorOutputPower()) && designator.getDesignatorOutputPower() != 0.0)
			ok = false;
		if (!knownObject.getCurrentOkState(testItemId))
			ok = false; // this test never passes after it once failed

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - output power was {}; output power shall be 0.0 before removal of Designator",
						designator.getObjectName(), designator.getDesignatorOutputPower());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				; // this test never passes after it once failed
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if Designator is staying max designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval() seconds in power zero state
	 */
	private void testDoNotStayLongInPowerZeroStateWarningOnly(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DoNotStayLongInPowerZeroStateWarningOnly;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		double timePassedSincePowerZero = 0;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorOutputPower()) && designator.getDesignatorOutputPower() == 0.0) {
			timePassedSincePowerZero = 1e-3 * (tNow - designator.getAttrTime(HlaDesignator.getAttrIdDesignatorOutputPower()));
			ok = timePassedSincePowerZero <= designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval();
		}
		if (!knownObject.getCurrentOkState(testItemId))
			ok = false; // this test never passes after it once failed

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok) {
				logger.warn(String.format(Locale.ROOT,
						"Designator %s - already %.3f seconds passed after setting output power to zero; TC parameters allow max %.3f seconds between setting output power to zero and removal",
						designator.getObjectName(), timePassedSincePowerZero, designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval()));
			} else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				; // this test never passes after it once failed
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if Designator is removed max designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval() seconds after power has been set to zero
	 */
	private void testRemovedQuicklyAfterPowerZeroWarningOnly(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.RemovedQuicklyAfterPowerZeroWarningOnly;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || !designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		double timePassedBetweenPowerZeroAndRemoval = 0;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDesignatorOutputPower()) && designator.getDesignatorOutputPower() == 0.0) {
			timePassedBetweenPowerZeroAndRemoval = 1e-3 * (designator.getTimeOfRemoval() - designator.getAttrTime(HlaDesignator.getAttrIdDesignatorOutputPower()));
			ok = timePassedBetweenPowerZeroAndRemoval <= designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval();
		}
		if (!knownObject.getCurrentOkState(testItemId))
			ok = false; // this test never passes after it once failed

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok) {
				logger.warn(String.format(Locale.ROOT,
						"Designator %s - was removed %.3f seconds after setting output power to zero; TC parameters allow max %.3f seconds between setting output power to zero and removal",
						designator.getObjectName(), timePassedBetweenPowerZeroAndRemoval, designatorTcParam.getMaxTimeBetweenPowerZeroAndDesignatorRemoval()));
			} else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				; // this test never passes after it once failed
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if designator is removed long time after host has been removed
	 */
	private void testDesignatorRemovedBeforeHostRemoved(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DesignatorRemovedBeforeHostRemoved;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || !designator.atLeastOneAttributeHasBeenSet())
			return;

		// Get hostEntity; if no host entity, no further testing
		HlaBaseEntity hostEntity = null;
		HlaBaseEntity hostEntityCandidate = null;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdEntityIdentifier()))
			hostEntityCandidate = getBaseEntityByEntityId(designator.getEntityIdentifier());
		if (hostEntityCandidate != null)
			hostEntity = hostEntityCandidate;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdHostObjectIdentifier()))
			hostEntityCandidate = getBaseEntityByObjectName(designator.getHostObjectIdentifier());
		if (hostEntityCandidate != null)
			hostEntity = hostEntityCandidate;
		if (hostEntity == null)
			return;

		// If host alive, no further testing
		if (!hostEntity.isRemoved())
			return;

		// Check if ok
		boolean ok = true;
		double maxAllowedTime = designatorTcParam.getMaxTimeBetweenHostRemovalAndDesignatorRemoval();
		String errorMsg = "";
		if (designator.isRemoved()) {
			double timePassedBetweenHostRemovalAndDesignatorRemoval = 1e-3 *(designator.getTimeOfRemoval() - hostEntity.getTimeOfRemoval());
			ok = timePassedBetweenHostRemovalAndDesignatorRemoval <= maxAllowedTime;
			errorMsg = String.format(Locale.ROOT,
					"Designator %s - was removed %.3f seconds after host entity removal; TC parameters allow max %.3f seconds",
					designator.getObjectName(), timePassedBetweenHostRemovalAndDesignatorRemoval, maxAllowedTime);
		}
		if (!designator.isRemoved()) {
			double timePassedSinceHostRemoval = 1e-3 *(tNow - hostEntity.getTimeOfRemoval());
			ok = timePassedSinceHostRemoval <= maxAllowedTime;
			errorMsg = String.format(Locale.ROOT,
					"Designator %s - shall be removed max %.3f seconds after host entity removal (according to TC parameters); already passed %.3f seconds since host entity removal",
					designator.getObjectName(), maxAllowedTime, timePassedSinceHostRemoval);
		}

		if (!knownObject.getCurrentOkState(testItemId))
			ok = false; // this test never passes after it once failed

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error(errorMsg);
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				; // this test never passes after it once failed
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if DR set to static and SpotLinearAccelerationVector set to other value than zero
	 */
	private void testDrStaticAccelerationNotSetOrZero(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DrStaticAccelerationNotSetOrZero;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm()) && designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.Static
				&& designator.attrHasBeenSet(HlaDesignator.getAttrIdSpotLinearAccelerationVector())) {
			AccelerationVectorStruct acc = designator.getSpotLinearAccelerationVector();
			if (acc != null) {
				if (acc.getXAcceleration() != 0 || acc.getYAcceleration() != 0 || acc.getZAcceleration() != 0)
					ok = false;
			}
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - when DR algorithm is set to static, SpotLinearAccelerationVector shall be set to zero (or not be set at all)",
						designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - DR algorithm is static, SpotLinearAccelerationVector now has been set to zero",
						designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if DR set to non static and SpotLinearAccelerationVector not set
	 */
	private void testDrNonStaticAccelerationSet(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DrNonStaticAccelerationSet;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm()) && designator.getDeadReckoningAlgorithm() != DeadReckoningAlgorithmEnum8.Static) {
			ok = designator.attrHasBeenSet(HlaDesignator.getAttrIdSpotLinearAccelerationVector());
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - when DR algorithm is set to non static, SpotLinearAccelerationVector shall be set",
						designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - DR algorithm is non static, SpotLinearAccelerationVector now has been set", designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Ok if DR set to Static or FVW
	 */
	private void testDrAlgoStaticOrFvw(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DrAlgoStaticOrFvw;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		boolean ok = true;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())) {
			ok = designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.Static
					|| designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.DRM_FVW;
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - deadreckoning algorithm shall be Static or FVW", designator.getObjectName());
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - deadreckoning algorithm is set to Static or FVW", designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if a spot location update is not provided within <heartbeat> seconds (<heartbeat> defined in TC parameters)
	 */
	private void testDrUpdateOnHeartbeat(KnownObject knownObject, boolean calledOnDesignatorUpdate) {
		TestItemIds testItemId = TestItemIds.DrUpdateOnHeartbeat;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		double heartbeat = 0;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())) {
			if (designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.Static) {
				heartbeat = designatorTcParam.getHeartbeatDrStatic();
			} else {
				heartbeat = designatorTcParam.getHeartbeatDrNonStatic();
			}
		}
		boolean ok = true;
		AttributeHandle hAttr = HlaDesignator.getAttrIdDesignatorSpotLocation();
		if (designator.attrHasBeenSet(hAttr) && designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())) {
			double timePassedBetweenUpdates = 1e-3 * (tNow - designator.getAttrTime(hAttr));
			if (calledOnDesignatorUpdate) {
				HlaDesignator prevDesignator = knownObject.getPrevDesignator();
				if (prevDesignator != null && prevDesignator.attrHasBeenSet(hAttr) && designator.getAttrSetInLastReflect(hAttr)) {
						timePassedBetweenUpdates = 1e-3 * (designator.getAttrTime(hAttr) - prevDesignator.getAttrTime(hAttr));
				}
			}
			if (designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.Static && heartbeat > 0)
				ok = timePassedBetweenUpdates <= 1.1 * heartbeat; // 10% margin allowed for heartbeat
			else if (designator.getDeadReckoningAlgorithm() != DeadReckoningAlgorithmEnum8.Static && heartbeat > 0)
				ok = timePassedBetweenUpdates <= 1.1 * heartbeat; // 10% margin allowed for heartbeat
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error("Designator {} - a spot location update shall be provided at least every {} seconds (heartbeat) (10 %% margin allowed)",
						designator.getObjectName(), heartbeat);
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info("Designator {} - a spot location update has been provided again (heartbeat)", designator.getObjectName());
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	/**
	 * Not ok if a spot location update has been provided too soon (heartbeat) without exceeding the predetermined threshold
	 */
	private void testDrPositionExtrapolation(KnownObject knownObject) {
		TestItemIds testItemId = TestItemIds.DrPositionExtrapolation;
		TestItem testItem = testItems.get(testItemId);
		if (!testItem.getEnabled())
			return;

		HlaDesignator designator = knownObject.get(HlaDesignator.class);
		if (designator == null || designator.isRemoved() || !designator.atLeastOneAttributeHasBeenSet())
			return;
		double heartbeat = 0;
		double distanceThreshold = 0;
		if (designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())) {
			if (designator.getDeadReckoningAlgorithm() == DeadReckoningAlgorithmEnum8.Static) {
				heartbeat = designatorTcParam.getHeartbeatDrStatic();
				distanceThreshold = designatorTcParam.getDistanceThresholdDrStatic();
			} else {
				heartbeat = designatorTcParam.getHeartbeatDrNonStatic();
				distanceThreshold = designatorTcParam.getDistanceThresholdDrNonStatic();
			}
		}
		AttributeHandle hAttr = HlaDesignator.getAttrIdDesignatorSpotLocation();
		HlaDesignator prevDesignator = knownObject.getPrevDesignator();
		double dist = 0;
		boolean ok = true;
		if (prevDesignator != null && prevDesignator.attrHasBeenSet(hAttr) && designator.getAttrSetInLastReflect(hAttr)
				&& designator.attrHasBeenSet(HlaDesignator.getAttrIdDeadReckoningAlgorithm())) {
			double timePassedBetweenUpdates = 1e-3 * (designator.getAttrTime(hAttr) - prevDesignator.getAttrTime(hAttr));
			WorldLocationStruct deadreckonedPos = computeDeadreckonedSpotLocation(prevDesignator, designator.getAttrTime(hAttr));
			WorldLocationStruct newPos = designator.getDesignatorSpotLocation();
			dist = distance(newPos, deadreckonedPos);
			ok = dist >= 0.9 * distanceThreshold || timePassedBetweenUpdates >= 0.9 * heartbeat; // 10% margin allowed for distance tolerance and for heartbeat
		}

		if (ok != knownObject.getCurrentOkState(testItemId)) {
			if (!ok)
				logger.error(String.format(Locale.ROOT,
						"Designator %s - a spot location update shall be provided when the discrepancy between the actual position (as determined by its own internal model) and its dead reckoned position (as determined by using specified dead reckoning algorithm) exceeds the predetermined threshold of %.3f meters; discrepancy was only %.3f meters",
						designator.getObjectName(), distanceThreshold, dist));
			else if (knownObject.alwaysReportWhenTestPassesAfterFail)
				logger.info(String.format("Designator %s - a spot location update has been provided correctly", designator.getObjectName()));
			knownObject.setCurrentOkState(testItemId, ok);
		}

		if (!ok)
			testItem.resultOk = false;
	}

	private void doEntityReferenceTests(KnownObject knownObject) {
		testHostEntityIdRefersToExistingEntity(knownObject);
		testHostObjectNameRefersToExistingEntity(knownObject);
		testHostEntityIdAndHostObjectNameReferToSameEntity(knownObject);
		testDesignatedObjectIdIsEmptyOrRefersToExistingEntity(knownObject);
	}

	// Do tests that have to be executed whenever a base entity has been discovered or removed
	private void doEntityReferenceTestsForAllDesignators() {
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			KnownObject knownObject = knownObjectEntry.getValue();
			HlaDesignator designator = knownObjectEntry.getValue().get(HlaDesignator.class);
			if (designator != null)
				doEntityReferenceTests(knownObject);
		}
	}

	private void doTestsOnDesignatorUpdate(KnownObject knownObject) {
		doEntityReferenceTests(knownObject);

		testMandatoryAttributesHaveBeenSet(knownObject);
		testMandatoryWarningOnlyAttributesHaveBeenSet(knownObject);
		testMultipleSettingOfStaticAttribute(knownObject);
		testNoAttributeUpdatesWhilePowerZero(knownObject);
		testOutputPowerIsNonNegative(knownObject);
		testEmissionWavelengthIsNonNegative(knownObject);
		testRelSpotLocNotSetOrZeroIfNoDesignatedObject(knownObject);
		testDrStaticAccelerationNotSetOrZero(knownObject);
		testDrNonStaticAccelerationSet(knownObject);
		testDrAlgoStaticOrFvw(knownObject);
		testDrUpdateOnHeartbeat(knownObject, true);
		testDrPositionExtrapolation(knownObject);
	}

	private void doTestsOnDesignatorRemoval(KnownObject knownObject) {
		setDetectedAtLeastOneDesignatorRemoval();
		testPowerZeroBeforeRemoval(knownObject);
		testRemovedQuicklyAfterPowerZeroWarningOnly(knownObject);
		testDesignatorRemovedBeforeHostRemoved(knownObject);
	}

	public void doTestsOnBaseEntityCreation() {
		doEntityReferenceTestsForAllDesignators();
	}

	public void doTestsOnBaseEntityRemoval() {
		doEntityReferenceTestsForAllDesignators();

		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			KnownObject knownObject = knownObjectEntry.getValue();
			HlaDesignator designator = knownObjectEntry.getValue().get(HlaDesignator.class);
			if (designator != null) {
				testDesignatorRemovedBeforeHostRemoved(knownObject);
			}
		}
	}

	public void doPeriodicTests() {
		// Check if designator is removed quickly after output power has been set to zero
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			KnownObject knownObject = knownObjectEntry.getValue();
			HlaDesignator designator = knownObjectEntry.getValue().get(HlaDesignator.class);
			if (designator != null) {
				testDoNotStayLongInPowerZeroStateWarningOnly(knownObject);
			}
		}

		// Check if designator is (or has been) removed when host is removed
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			KnownObject knownObject = knownObjectEntry.getValue();
			HlaDesignator designator = knownObjectEntry.getValue().get(HlaDesignator.class);
			if (designator != null) {
				testDesignatorRemovedBeforeHostRemoved(knownObject);
			}
		}

		// Check if spot location is updated on heartbeat
		for (Map.Entry<ObjectInstanceHandle, KnownObject> knownObjectEntry : knownObjects.entrySet()) {
			KnownObject knownObject = knownObjectEntry.getValue();
			HlaDesignator designator = knownObjectEntry.getValue().get(HlaDesignator.class);
			if (designator != null) {
				testDrUpdateOnHeartbeat(knownObject, false);
			}
		}
	}

	public void timerCallback() {
		tNow = System.currentTimeMillis();
		doPeriodicTests();
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void discoverObjectInstance(final ObjectInstanceHandle theObject, final ObjectClassHandle theObjectClass,
			final String objectName) throws FederateInternalError {
		logger.trace("discovered object - class name: {}; object name: {}", theObjectClass.toString(), objectName);

		tNow = System.currentTimeMillis();

		// Create new local HlaBaseEntity object; store in knownObjects; do tests for all designators
		if (theObjectClass.equals(HlaBaseEntity.getClassId())) {
			logger.info("discovered BaseEntity {}", objectName);
			if (knownObjects.containsKey(theObject)) {
				final KnownObject removedKnownObject = knownObjects.remove(theObject);
				if (removedKnownObject != null)
					logger.warn("discovered BaseEntity {}; removed another BaseEntity {} with same ObjectInstanceHandle",
							objectName, removedKnownObject.get(HlaBaseEntity.class).getObjectName());
			} else {
				logger.debug("not yet in knownObjects");
			}
			final HlaBaseEntity baseEntity = new HlaBaseEntity(theObject);
			if (baseEntity != null) {
				baseEntity.setTimeOfCreation(tNow);
				baseEntity.setObjectName(objectName);
				final KnownObject knownObject = new KnownObject(baseEntity);
				knownObjects.put(theObject, knownObject);
				logger.trace("BaseEntity {} has been stored in knownObjects", objectName);
				doTestsOnBaseEntityCreation();
			}
		}

		// Create new local HlaDesignator object; store in knownObjects
		if (theObjectClass.equals(HlaDesignator.getClassId())) {
			logger.info("discovered Designator {}", objectName);
			setDetectedAtLeastOneDesignatorCreation();
			if (knownObjects.containsKey(theObject)) {
				final KnownObject removedKnownObject = knownObjects.remove(theObject);
				if (removedKnownObject != null)
					logger.warn("discovered Designator {}; removed another Designator {} with same ObjectInstanceHandle",
							objectName, removedKnownObject.get(HlaBaseEntity.class).getObjectName());
			} else {
				logger.debug("not yet in knownObjects");
			}
			final HlaDesignator designator = new HlaDesignator(theObject);
			if (designator != null) {
				designator.setTimeOfCreation(tNow);
				designator.setObjectName(objectName);
				final KnownObject knownObject = new KnownObject(designator);
				knownObjects.put(theObject, knownObject);
				logger.trace("Designator {} has been stored in knownObjects", objectName);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void discoverObjectInstance(final ObjectInstanceHandle theObject, final ObjectClassHandle theObjectClass, final String objectName, final FederateHandle producingFederate) throws FederateInternalError {
		logger.warn("producingFederate in discoverObjectInstance call not used");
		discoverObjectInstance(theObject, theObjectClass, objectName);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void removeObjectInstance(final ObjectInstanceHandle theObject, final byte[] userSuppliedTag,
			final OrderType sentOrdering, final FederateAmbassador.SupplementalRemoveInfo removeInfo) {
		logger.trace("removeObjectInstance for object {}", theObject.toString());

		tNow = System.currentTimeMillis();

		// Handle removal, if theObject is a BaseEntity; do tests for all designators
		if (knownObjects.containsKey(theObject)) {
			KnownObject knownObject = knownObjects.get(theObject);
			HlaBaseEntity baseEntity = knownObject.get(HlaBaseEntity.class);
			if (baseEntity != null) {
				knownObject.storeInHistory();
				baseEntity.setTimeOfRemoval(tNow);
				logger.info("BaseEntity {} has left", baseEntity.getObjectName());
				doTestsOnBaseEntityRemoval();
			}
		}

		// Handle removal, if theObject is a Designator
		if (knownObjects.containsKey(theObject)) {
			KnownObject knownObject = knownObjects.get(theObject);
			HlaDesignator designator = knownObject.get(HlaDesignator.class);
			if (designator != null) {
				knownObject.storeInHistory();
				designator.setTimeOfRemoval(tNow);
				logger.info("Designator {} has left", designator.getObjectName());
				doTestsOnDesignatorRemoval(knownObject);
			}
		}
	}

	/**
	 * @param theObject
	 *          the object instance handle
	 * @param theAttributes
	 *          the map of attribute handle / value
	 */
	private void doReflectAttributeValues(final ObjectInstanceHandle theObject, final AttributeHandleValueMap theAttributes) {
		logger.trace("doReflectAttributeValues for object {}", theObject.toString());

		tNow = System.currentTimeMillis();

		// Handle BaseEntity
		if (knownObjects.containsKey(theObject)) {
			HlaBaseEntity baseEntity = knownObjects.get(theObject).get(HlaBaseEntity.class);
			if (baseEntity != null && theAttributes.size() > 0) {

				// Check if baseEntity exists
				if (baseEntity.isRemoved()) {
					// impossible to use logInternalErrorAsWarningAndThrowInconclusive here; so using TestItemIds.InternalError
					logger.warn("INTERNAL ERROR - reflectAttributeValues has been called for already removed BaseEntity {}", baseEntity.getObjectName());
					testItems.get(TestItemIds.InternalError).resultOk = false;
				}

				// Decode and set the attribute values
				logger.debug("doReflectAttributeValues - object is known BaseEntity {}", baseEntity.getObjectName());
				KnownObject knownObject = knownObjects.get(theObject);
				knownObject.storeInHistory();
				baseEntity.reflectAttributeValues(theAttributes);
			}
		}

		// Handle Designator
		if (knownObjects.containsKey(theObject)) {
			HlaDesignator designator = knownObjects.get(theObject).get(HlaDesignator.class);
			if (designator != null && theAttributes.size() > 0) {

				// Check if designator exists
				if (designator.isRemoved()) {
					// impossible to use logInternalErrorAsWarningAndThrowInconclusive here; so using TestItemIds.InternalError
					logger.warn("INTERNAL ERROR - reflectAttributeValues has been called for already removed Designator {}", designator.getObjectName());
					testItems.get(TestItemIds.InternalError).resultOk = false;
				}

				// Decode and set the attribute values
				logger.debug("doReflectAttributeValues - object is known Designator {}", designator.getObjectName());
				KnownObject knownObject = knownObjects.get(theObject);
				knownObject.storeInHistory();
				if (!designator.reflectAttributeValues(theAttributes)) {
					TestItem testItem = testItems.get(TestItemIds.Decoding);
					if (testItem.getEnabled())
						testItem.resultOk = false;
				}

				doTestsOnDesignatorUpdate(knownObject);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void reflectAttributeValues(final ObjectInstanceHandle theObject, final AttributeHandleValueMap theAttributes,
			final byte[] userSuppliedTag, final OrderType sentOrdering, final TransportationTypeHandle theTransport,
			final SupplementalReflectInfo reflectInfo) throws FederateInternalError {
		doReflectAttributeValues(theObject, theAttributes);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void reflectAttributeValues(final ObjectInstanceHandle theObject, final AttributeHandleValueMap theAttributes,
			final byte[] userSuppliedTag, final OrderType sentOrdering, final TransportationTypeHandle theTransport,
			@SuppressWarnings("rawtypes") final LogicalTime theTime, final OrderType receivedOrdering, final SupplementalReflectInfo reflectInfo)
					throws FederateInternalError {
		doReflectAttributeValues(theObject, theAttributes);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void reflectAttributeValues(final ObjectInstanceHandle theObject, final AttributeHandleValueMap theAttributes,
			final byte[] userSuppliedTag, final OrderType sentOrdering, final TransportationTypeHandle theTransport,
			@SuppressWarnings("rawtypes") final LogicalTime theTime, final OrderType receivedOrdering, final MessageRetractionHandle retractionHandle,
			final SupplementalReflectInfo reflectInfo) throws FederateInternalError {
		doReflectAttributeValues(theObject, theAttributes);
	}
}
