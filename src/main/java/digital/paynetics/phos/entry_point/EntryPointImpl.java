package digital.paynetics.phos.entry_point;


import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import digital.paynetics.phos.kernel.common.crypto.EncDec;
import digital.paynetics.phos.kernel.common.emv.Outcome;
import digital.paynetics.phos.kernel.common.emv.entry_point.EntryPoint;
import digital.paynetics.phos.kernel.common.emv.entry_point.misc.IntermediateOutcomeStore;
import digital.paynetics.phos.kernel.common.emv.entry_point.misc.MessageStore;
import digital.paynetics.phos.kernel.common.emv.entry_point.misc.TransactionData;
import digital.paynetics.phos.kernel.common.emv.entry_point.preprocessing.EntryPointPreprocessor;
import digital.paynetics.phos.kernel.common.emv.entry_point.selection.ApplicationSelector;
import digital.paynetics.phos.kernel.common.emv.entry_point.selection.SelectedApplication;
import digital.paynetics.phos.kernel.common.emv.kernel.common.CommonDolDataPreparer;
import digital.paynetics.phos.kernel.common.emv.kernel.common.EmvException;
import digital.paynetics.phos.kernel.common.emv.kernel.common.Kernel;
import digital.paynetics.phos.kernel.common.emv.kernel.common.KernelType;
import digital.paynetics.phos.kernel.common.emv.kernel.common.TlvMap;
import digital.paynetics.phos.kernel.common.emv.kernel.common.TlvMapImpl;
import digital.paynetics.phos.kernel.common.emv.kernel.common.TlvMapReadOnly;
import digital.paynetics.phos.kernel.common.emv.tag.EmvTag;
import digital.paynetics.phos.kernel.common.emv.tag.Tlv;
import digital.paynetics.phos.kernel.common.emv.tag.TlvException;
import digital.paynetics.phos.kernel.common.emv.ui.ContactlessTransactionStatus;
import digital.paynetics.phos.kernel.common.emv.ui.EntryPointUiRequester;
import digital.paynetics.phos.kernel.common.emv.ui.StandardMessages;
import digital.paynetics.phos.kernel.common.emv.ui.UserInterfaceRequest;
import digital.paynetics.phos.kernel.common.misc.CardAppConfiguration;
import digital.paynetics.phos.kernel.common.misc.CardApplication;
import digital.paynetics.phos.kernel.common.misc.CertificateData;
import digital.paynetics.phos.kernel.common.misc.Currency;
import digital.paynetics.phos.kernel.common.misc.NfcConnectionLostException;
import digital.paynetics.phos.kernel.common.misc.PreprocessedApplication;
import digital.paynetics.phos.kernel.common.misc.TerminalConfig;
import digital.paynetics.phos.kernel.common.misc.TimeProvider;
import digital.paynetics.phos.kernel.common.misc.TransactionTimestamp;
import digital.paynetics.phos.kernel.common.misc.TransactionType;
import digital.paynetics.phos.kernel.common.nfc.NfcManager;
import digital.paynetics.phos.kernel.common.nfc.iso_dep.IsoDepWrapper;
import digital.paynetics.phos.kernel.common.nfc.transceiver.Transceiver;
import digital.paynetics.phos.kernel.mastercard.MastercardKernel;
import digital.paynetics.phos.kernel.mastercard.misc.MastercardErrorIndication;
import digital.paynetics.phos.kernel.mastercard.misc.MastercardMagstripeFailedCounter;
import digital.paynetics.phos.kernel.mastercard.misc.MastercardMessageIdentifier;
import digital.paynetics.phos.kernel.mastercard.misc.MessageStoreMc;
import digital.paynetics.phos.kernel.mastercard.misc.OutcomePresenter;
import digital.paynetics.phos.kernel.mastercard.torn.TornTransactionLog;
import digital.paynetics.phos.kernel.mastercard.torn.TornTransactionLogRecord;
import digital.paynetics.phos.kernel.visa.VisaKernel;
import hirondelle.date4j.DateTime;
import java8.util.Optional;

import static digital.paynetics.phos.kernel.mastercard.MastercardKernelImpl.logOutcome;


public class EntryPointImpl implements EntryPoint, NfcManager.Listener {
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(this.getClass());

    private final EntryPointPreprocessor entryPointPreprocessor;
    private final Provider<ApplicationSelector> applicationSelectorProvider;
    private final TimeProvider timeProvider;

    private final CommonDolDataPreparer commonDolDataPreparer;

    private Listener listener;

    private List<PreprocessedApplication> appsPreprocessed;
    private EntryPointUiRequester entryPointUiRequester;
    private NfcManager nfcManager;

    private final Provider<MastercardKernel> mastercardKernelProvider;
    private final Provider<VisaKernel> visaKernelProvider;
    private final MastercardMagstripeFailedCounter mastercardMagstripeFailedCounter;
    private final IntermediateOutcomeStore intermediateOutcomeStore;
    private final MessageStore messageStore;
    private final MessageStoreMc messageStoreMc;
    private final TornTransactionLog tornTransactionLog;
    private final TornTransactionLogPersister tornTransactionLogPersister;
    private final boolean isAutorunOn;
    private final boolean dontLogDataRecord;
    private final boolean useLightLogging;

    private State state = State.IDLE;

    private TransactionData transactionData;
    private ApplicationSelector applicationSelector;
    private TerminalConfig terminalConfig;
    private CertificateData certificateData;

    private boolean isInitialized = false;

    private volatile boolean isStopSignalReceived = false;

    private Kernel processingKernel;
    private volatile Transceiver transceiver;
    private volatile Transceiver transceiverPostponed;

    private EncDec encDec;


    private long startPpse; // used to measure the time between PPSE and kernel end

    private Optional<KernelType> lastKernelType = Optional.empty();


    @Inject
    public EntryPointImpl(EntryPointPreprocessor entryPointPreprocessor,
                          Provider<ApplicationSelector> applicationSelectorProvider,
                          TimeProvider timeProvider,
                          CommonDolDataPreparer commonDolDataPreparer,
                          Provider<MastercardKernel> mastercardKernelProvider,
                          Provider<VisaKernel> visaKernelProvider,
                          MastercardMagstripeFailedCounter mastercardMagstripeFailedCounter,
                          IntermediateOutcomeStore intermediateOutcomeStore,
                          MessageStore messageStore,
                          MessageStoreMc messageStoreMc,
                          TornTransactionLog tornTransactionLog,
                          TornTransactionLogPersister tornTransactionLogPersister,
                          @Named("autorun on") boolean isAutorunOn,
                          @Named("dont log data record") boolean dontLogDataRecord,
                          @Named("use light logging") boolean useLightLogging) {

        this.entryPointPreprocessor = entryPointPreprocessor;
        this.applicationSelectorProvider = applicationSelectorProvider;
        this.timeProvider = timeProvider;
        this.commonDolDataPreparer = commonDolDataPreparer;
        this.mastercardKernelProvider = mastercardKernelProvider;
        this.visaKernelProvider = visaKernelProvider;
        this.mastercardMagstripeFailedCounter = mastercardMagstripeFailedCounter;
        this.intermediateOutcomeStore = intermediateOutcomeStore;
        this.messageStore = messageStore;
        this.messageStoreMc = messageStoreMc;
        this.tornTransactionLog = tornTransactionLog;
        this.tornTransactionLogPersister = tornTransactionLogPersister;
        this.isAutorunOn = isAutorunOn;
        this.dontLogDataRecord = dontLogDataRecord;
        this.useLightLogging = useLightLogging;
    }


    @Override
    public void init(Listener listener,
                     EntryPointUiRequester entryPointUiRequester,
                     NfcManager nfcManager,
                     TerminalConfig terminalConfig,
                     CertificateData certificateData,
                     EncDec encDec) {

        this.listener = listener;
        this.entryPointUiRequester = entryPointUiRequester;
        this.nfcManager = nfcManager;
        nfcManager.init(this);
        this.terminalConfig = terminalConfig;
        this.certificateData = certificateData;
        this.encDec = encDec;
        isInitialized = true;
        intermediateOutcomeStore.clear();
    }


    @Override
    public Optional<Outcome> startA_preProcessing(
            List<CardApplication> apps,
            List<CardAppConfiguration> appConfs,
            int amountAuthorized,
            int amountOther,
            Currency currency,
            TransactionType transactionType) {

        if (!isInitialized) {
            throw new IllegalStateException("Not initialized");
        }

        if (state != State.IDLE) {
            throw new IllegalStateException("Can be called only in IDLE state");
        }
        state = State.PRE_PROCESSING;


        if (transactionType == TransactionType.CASHBACK) {
            amountAuthorized += amountOther;
        }

        logger.debug("Start A - Pre-processing");
        transactionData = new TransactionData(amountAuthorized, amountOther, currency, transactionType);


        if (entryPointUiRequester == null) {
            throw new IllegalStateException("Did you forgot to call init() first?");
        }


        appsPreprocessed = entryPointPreprocessor.preProcess(apps,
                appConfs,
                amountAuthorized,
                amountOther,
                currency,
                transactionType);

        boolean allAppsNotAllowed = true;

        // Requirement 3.1.1.13
        for (PreprocessedApplication app : appsPreprocessed) {
            if (!app.getIndicators().isContactlessApplicationNotAllowed()) {
                allAppsNotAllowed = false;
            }
        }

        listener.onPreProcessingEnded();
        Outcome oc = null;
        if (allAppsNotAllowed) {
            oc = Outcome.createTryAnotherInterface(null);
        } else {
            startB_protocolActivationInternal();
        }

        return Optional.ofNullable(oc);
    }


    @Override
    public Outcome startB_protocolActivationFromReaderDirect(IsoDepWrapper isoDepWrapper) {
        throw new UnsupportedOperationException();
    }


    @Override
    public void stopSignal() {
        logger.debug("STOP signal (state: {})", state);
        if (state == State.SELECTION) {
            isStopSignalReceived = true;
            transceiver.close();
        } else if (state == State.KERNEL_PROCESSING) {
            if (processingKernel != null) {
                boolean isAccepted = processingKernel.stopSignal();
                if (isAccepted) {
                    isStopSignalReceived = true;
                } else {
                    logger.debug("STOP signal ignored by the Mastercard kernel", state);
                }
            }
        }
    }


    @Override
    public List<Transceiver.StatItem> getTransceiverStats() {
        return transceiver.getStats();
    }


    @Override
    public void onNfcTag(Transceiver transceiver) {
        if (!isInitialized) {
            throw new IllegalStateException("Not initialized");
        }
        this.transceiver = transceiver;
        listener.onStartedReadingCard();
        startC_CombinationSelectionInternalA(transceiver);
    }


    private void startB_protocolActivationInternal() {
        if (state != State.PRE_PROCESSING) {
            throw new IllegalStateException("Can be called only in PRE_PROCESSING state");
        }
        state = State.WAITING_FOR_TAG;

        logger.debug("Start B - protocol activation/waiting for tag");
        // Req 3.2.1.1 not applicable because we are not started directly from the reader see
        // startB_protocolActivationFromReaderDirect

        // Req 3.2.1.2 is handled in the reader
        entryPointUiRequester.uiRequest(new UserInterfaceRequest(StandardMessages.PRESENT_CARD,
                ContactlessTransactionStatus.READY_TO_READ, 0, null, null, 0, null));

        // Req 3.2.1.3
        nfcManager.startPolling();
        listener.onStartedPolling();
        // Req 3.2.1.4, 3.2.1.5 - not applicable - Android cannot provide ways to detect collisions

        // Req 3.2.1.6 not applicable - we don't use higher layer commands
    }


    private void startC_CombinationSelectionInternalA(Transceiver transceiver) {
        if (state != State.WAITING_FOR_TAG) {
            logger.warn("startC_CombinationSelectionInternalA can be called only in WAITING_FOR_TAG state");
            transceiverPostponed = transceiver;
            return;
        }

        state = State.SELECTION;
        logger.debug("Start C - Selection");

        // req 3.3.2.1 - not applicable, we are not started at B

        try {
            startPpse = timeProvider.getVmTime();
            applicationSelector = applicationSelectorProvider.get();
            Optional<Outcome> ocO = applicationSelector.init(appsPreprocessed, transceiver);
            if (!ocO.isPresent()) {
                startC_CombinationSelectionInternalCommon(transceiver);
            } else {
                state = State.ENDED;
                nfcManager.stopPolling();
                Outcome oc = ocO.get();
//                logger.debug("(outc) Entry point outcome: {}", OutcomePresenter.present(oc));
                if (!useLightLogging) {
                    logOutcome(logger, oc, dontLogDataRecord);
                }
                listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.empty());
            }
        } catch (NfcConnectionLostException e) {
            logger.warn("Tag lost during selection");
            if (isStopSignalReceived) {
                logger.debug("Stop signal processing");
                nfcManager.exit();
                state = State.ENDED;
                Outcome oc = MastercardKernel.createStopOutcome();
                if (!useLightLogging) {
                    logOutcome(logger, oc, dontLogDataRecord);
                }
                listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.empty());
            } else {
                logger.debug("Switching back to waiting for tag");
                state = State.WAITING_FOR_TAG;
                MastercardErrorIndication ei = MastercardErrorIndication.createL1Error(MastercardErrorIndication.L1Error.TIME_OUT,
                        MastercardMessageIdentifier.NOT_AVAILABLE);
                TlvMap dd = new TlvMapImpl();
                dd.add(ei.asErrorIndicationTlv());

                Outcome oc = Outcome.createTryAgainOutcome(dd.asList());
                intermediateOutcomeStore.add(oc);
                if (!useLightLogging) {
                    logOutcome(logger, oc, dontLogDataRecord);
                }
                listener.onEndedReadingCard();
            }
        } catch (IOException e) {
            logger.warn("Error while selecting app: {}", e);
            logger.debug("Switching back to waiting for tag");
            state = State.WAITING_FOR_TAG;
            listener.onEndedReadingCard();
        } catch (TlvException | EmvException e) {
            logger.warn("Error while selecting app: {}", e);

            Outcome.Builder b = new Outcome.Builder(Outcome.Type.END_APPLICATION);

            UserInterfaceRequest uiReq = new UserInterfaceRequest(StandardMessages.TRY_ANOTHER_CARD,
                    ContactlessTransactionStatus.NOT_READY,
                    13,
                    null,
                    null,
                    0,
                    null);
            b.uiRequestOnOutcome(uiReq);

            MastercardErrorIndication ei = MastercardErrorIndication.createL2Error(MastercardErrorIndication.L2Error.PARSING_ERROR,
                    MastercardMessageIdentifier.ERROR_OTHER_CARD);
            List<Tlv> dd = new ArrayList<>();
            dd.add(ei.asErrorIndicationTlv());
            b.discretionaryData(dd);
            Outcome oc = b.build();
            if (!useLightLogging) {
                logOutcome(logger, oc, dontLogDataRecord);
            }
            listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.empty());
//            logger.debug("(outc) Entry point outcome: {}", OutcomePresenter.present(oc));
            logger.debug("Switching back to waiting for tag");
            state = State.WAITING_FOR_TAG;
            listener.onEndedReadingCard();
        }
    }


    private void startC_CombinationSelectionInternalD(Transceiver transceiver) {
        if (state != State.KERNEL_PROCESSING) {
            throw new IllegalStateException("Can be called only in KERNEL_PROCESSING state");
        }

        state = State.SELECTION;

        try {
            startC_CombinationSelectionInternalCommon(transceiver);
        } catch (IOException e) {
            logger.warn("Error while selecting app: {}", e);
            logger.debug("Switching back to waiting for tag");
            state = State.WAITING_FOR_TAG;
            listener.onEndedReadingCard();
        }
    }


    private void startC_CombinationSelectionInternalCommon(Transceiver transceiver) throws IOException {

        Optional<SelectedApplication> selectedApp = applicationSelector.select(transceiver);
        if (selectedApp.isPresent()) {
            if (isStopSignalReceived && selectedApp.get().getCandidate().getFinalKernelType() == KernelType.MASTERCARD) {
                Outcome oc = MastercardKernel.createStopOutcome();
                logOutcome(logger, oc, dontLogDataRecord);
                listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.empty());
                state = State.ENDED;
                listener.onEndedReadingCard();
            } else {
                // Req 3.4.1.1
                startD_kernelActivation(transceiver, selectedApp.get());
            }
        } else {
            Outcome.Builder b = new Outcome.Builder(Outcome.Type.END_APPLICATION);

            UserInterfaceRequest uiReq = new UserInterfaceRequest(StandardMessages.TRY_ANOTHER_CARD,
                    ContactlessTransactionStatus.NOT_READY,
                    13,
                    null,
                    null,
                    0,
                    null);
            b.uiRequestOnOutcome(uiReq);

            MastercardErrorIndication ei = MastercardErrorIndication.createL2Error(MastercardErrorIndication.L2Error.EMPTY_CANDIDATE_LIST,
                    MastercardMessageIdentifier.ERROR_OTHER_CARD);
            List<Tlv> dd = new ArrayList<>();
            dd.add(ei.asErrorIndicationTlv());
            b.discretionaryData(dd);
            Outcome oc = b.build();
            logOutcome(logger, oc, dontLogDataRecord);
            listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.empty());
            logger.debug("Switching back to waiting for tag");
            state = State.WAITING_FOR_TAG;
            listener.onEndedReadingCard();
        }
    }


    private void startD_kernelActivation(Transceiver transceiver, SelectedApplication selected) {
        if (state != State.SELECTION) {
            throw new IllegalStateException("Can be called only in SELECTION state");
        }
        state = State.KERNEL_PROCESSING;
        logger.debug("Start D - kernel activation");

        TransactionTimestamp ts = new TransactionTimestamp(DateTime.forInstant(timeProvider.getWallClockTime(),
                TimeZone.getTimeZone("UTC")));

        TlvMap commonDolData = commonDolDataPreparer.prepare(terminalConfig.getCountryCode(),
                terminalConfig.getTerminalType(),
                transactionData,
                ts,
                selected.getCandidate().getPreprocessedApplication().getAppConfig().getTlvConfigData());
        Outcome oc;
        lastKernelType = Optional.of(selected.getCandidate().getFinalKernelType());
        switch (selected.getCandidate().getFinalKernelType()) {
            case JCB_VISA:
                throw new UnsupportedOperationException();
            case MASTERCARD:
                logger.debug("++++++++ Will activate Mastercard kernel ++++++++");
                long start = timeProvider.getVmTime();
                oc = processWithMastercardKernel(transceiver, selected, commonDolData, ts);
                List<Transceiver.StatItem> stats = transceiver.getStats();
                long nfcTook = 0;
                for (Transceiver.StatItem si : stats) {
                    nfcTook += si.getTook();
                }
                logger.debug("Selection + kernel: {} ms, waiting for response: {} ms",
                        timeProvider.getVmTime() - startPpse, nfcTook);
                break;
            case VISA:
                logger.debug("++++++++ Will activate VISA kernel ++++++++");
                oc = processWithVisaKernel(transceiver, selected, commonDolData, ts);

                List<Transceiver.StatItem> stats2 = transceiver.getStats();
                long nfcTook2 = 0;
                for (Transceiver.StatItem si : stats2) {
                    nfcTook2 += si.getTook();
                }
                logger.debug("Selection + kernel: {} ms, waiting for response: {} ms",
                        timeProvider.getVmTime() - startPpse, nfcTook2);
                break;
            case AMERICAN_EXPRESS:
                throw new UnsupportedOperationException();
            case JCB:
                throw new UnsupportedOperationException();
            case DISCOVER:
                throw new UnsupportedOperationException();
            case UNIONPAY:
                throw new UnsupportedOperationException();
            default:
                // added just to keep the compiler happy
                throw new UnsupportedOperationException();
        }

        System.gc();
        logger.debug("-------- Ended kernel processing -------- ");

        if (!useLightLogging) {
            List<Outcome> imos = intermediateOutcomeStore.get();
            if (imos.size() > 0) {
                for (int i = 0; i < imos.size(); i++) {

                    logOutcome(logger, imos.get(i), dontLogDataRecord);
                }
            }
        }

        List<UserInterfaceRequest> uirList = messageStoreMc.getAll();

        if (!useLightLogging) {
            for (UserInterfaceRequest uir : uirList) {
                logger.debug("(outc) Message: \n    {}", OutcomePresenter.present(uir));
            }

            logOutcome(logger, oc, dontLogDataRecord);
        }

        List<Tlv> ddTlvs = oc.getDiscretionaryData();
        if (ddTlvs != null) {
            for (Tlv tlv : ddTlvs) {
                if (tlv.getTag() == EmvTag.ERROR_INDICATION) {
                    MastercardErrorIndication mei = MastercardErrorIndication.fromBytes(tlv.getValueBytes());
                    if (mei.hasError()) {
                        logger.debug("Error indication: {}", mei);
                    }
                    break;
                }
            }
        }

        switch (oc.getType()) {
            case SELECT_NEXT:
//                intermediateOutcomeStore.add(oc);
                startC_CombinationSelectionInternalD(transceiver);
                break;
            case TRY_AGAIN:
                state = State.WAITING_FOR_TAG;
                listener.onEndedReadingCard();
                break;
            case APPROVED:
                if (isAutorunOn) {
                    state = State.WAITING_FOR_TAG;
                    listener.onEndedReadingCard();
                } else {
                    nfcManager.stopPolling();
                    state = State.ENDED;
                }
                break;
            case DECLINED:
                if (isAutorunOn) {
                    state = State.WAITING_FOR_TAG;
                    listener.onEndedReadingCard();
                } else {
                    nfcManager.stopPolling();
                    state = State.ENDED;
                }
                break;
            case ONLINE_REQUEST:
                if (isAutorunOn) {
                    state = State.WAITING_FOR_TAG;
                    listener.onEndedReadingCard();
                } else {
                    nfcManager.stopPolling();
                    state = State.ENDED;
                }
                break;
            case TRY_ANOTHER_INTERFACE:
                if (isAutorunOn) {
                    state = State.WAITING_FOR_TAG;
                    listener.onEndedReadingCard();
                } else {
                    if (lastKernelType.isPresent()) {
                        // probably always it should be processed like in VISA, because TRY_ANOTHER_INTERFACE is final outcome
                        // but we are doing like this to prevent breaking Mastercard tests
                        // On next Mastercard L2 test - remove the "if" and leave only the code that is currently for VISA, i.e.
                        // stopPolling
                        if (lastKernelType.get() == KernelType.VISA) {
                            nfcManager.stopPolling();
                            state = State.ENDED;
                        } else {
                            state = State.WAITING_FOR_TAG;
                            listener.onEndedReadingCard();
                        }
                    }
                }
                break;
            case END_APPLICATION:
                if (isAutorunOn) {
                    state = State.WAITING_FOR_TAG;
                    listener.onEndedReadingCard();
                    nfcManager.startPolling();
                } else {
                    if (oc.getStart() == Outcome.Start.B) {
                        state = State.WAITING_FOR_TAG;
                        listener.onEndedReadingCard();
                    } else {
                        nfcManager.stopPolling();
                        state = State.ENDED;
                    }
                }
                break;
        }

        if (oc.getType() != Outcome.Type.SELECT_NEXT) {
            listener.onOutcome(oc, intermediateOutcomeStore.get(), messageStore.get(), Optional.of(selected));
        }

        if (transceiverPostponed != null) {
            Transceiver tmp = transceiverPostponed;
            transceiverPostponed = null;
            startC_CombinationSelectionInternalA(tmp);
        }
    }


    private Outcome processWithVisaKernel(Transceiver transceiver,
                                          SelectedApplication selected,
                                          TlvMap commonDolData,
                                          TransactionTimestamp ts) {

        VisaKernel visaKernel = visaKernelProvider.get();
        processingKernel = visaKernel;

        // we don't want to introduce synchronization just for stopping so we will stop a bit late, no big deal
        if (isStopSignalReceived) {
            processingKernel.stopSignal();
        }

        @SuppressWarnings("UnnecessaryLocalVariable")
        Outcome oc = visaKernel.process(transceiver,
                commonDolData,
                terminalConfig.getCountryCode(),
                transactionData,
                selected,
                ts,
                encDec
        );

        return oc;
    }


    private Outcome processWithMastercardKernel(Transceiver transceiver,
                                                SelectedApplication selected,
                                                TlvMap commonDolData,
                                                TransactionTimestamp ts) {

        MastercardKernel kernel = mastercardKernelProvider.get();
        processingKernel = kernel;

        // we don't want to introduce synchronization just for stopping so we will stop a bit late, no big deal
        if (isStopSignalReceived) {
            processingKernel.stopSignal();
        }


        List<TornTransactionLogRecord> ttl = tornTransactionLogPersister.load();
        if (!useLightLogging) {
            logger.debug("Torn transaction log loaded with size: {}", ttl.size());
        }
        tornTransactionLog.setLog(ttl);

        kernel.init(mastercardMagstripeFailedCounter,
                certificateData.getCaRidDb(),
                certificateData.getCertificateRevocationList(),
                encDec
        );

        try {
            Outcome oc = kernel.process(transceiver,
                    commonDolData,
                    terminalConfig.getCountryCode(),
                    transactionData,
                    selected,
                    ts
            );

            if (oc.isDiscretionaryDataPresent()) {
                logger.debug("Discretionary data size: {}", oc.getDiscretionaryData().size());
            }
            return oc;
        } catch (NfcConnectionLostException e) {
            logger.warn("Tag lost during kernel processing");
            if (isStopSignalReceived) {
                logger.debug("Stop signal processing");
                nfcManager.exit();

                @SuppressWarnings("UnnecessaryLocalVariable")
                Outcome oc = MastercardKernel.createStopOutcome();

                return oc;
            } else {
                logger.debug("Switching back to waiting for tag");
                MastercardErrorIndication ei = MastercardErrorIndication.createL1Error(MastercardErrorIndication.L1Error.TIME_OUT,
                        MastercardMessageIdentifier.TRY_AGAIN);


                UserInterfaceRequest uiReq = new UserInterfaceRequest(StandardMessages.PRESENT_CARD_AGAIN,
                        ContactlessTransactionStatus.READY_TO_READ,
                        0,
                        null,
                        null,
                        0,
                        null);

                Outcome.Builder b = new Outcome.Builder(Outcome.Type.END_APPLICATION);
                b.uiRequestOnRestart(uiReq);
                b.removalTimeout(0);
                b.start(Outcome.Start.B);
                b.discretionaryData(MastercardKernel.buildDiscretionaryData(kernel.isEmvMode(), kernel.getTlvDb(), ei));
                return b.build();
            }
        } catch (IOException e) {
            logger.warn("IO error {}", e.getMessage());
            return Outcome.createTryAgainOutcome(null);
        } finally {
            logger.debug("Torn transaction log size after kernel: {}", tornTransactionLog.getLog().size());
            tornTransactionLogPersister.save(tornTransactionLog.getLog());
        }
    }


    /**
     * Merges application tlvConfigData with defaultConfigTlvData. TLVs in tlvConfigData override matching TLVs in defaultConfigTlvData
     *
     * @param tlvConfigData
     * @param defaultConfigTlvData
     * @return
     */
    private TlvMapReadOnly mergeTlvConfigData(List<Tlv> tlvConfigData, TlvMapReadOnly defaultConfigTlvData) {
        TlvMap ret = new TlvMapImpl();
        ret.addAll(defaultConfigTlvData.asList());
        for (Tlv tlv : tlvConfigData) {
            ret.updateOrAdd(tlv);
        }

        return ret;
    }


    private Outcome createTryAnotherCardOutcome() {
        Outcome.Builder b = new Outcome.Builder(Outcome.Type.END_APPLICATION);

        UserInterfaceRequest uiReq = new UserInterfaceRequest(StandardMessages.TRY_ANOTHER_CARD,
                ContactlessTransactionStatus.READY_TO_READ,
                0,
                null,
                null,
                0,
                null);
        b.uiRequestOnOutcome(uiReq);
        b.fieldOffRequest(13);
        b.removalTimeout(0);

        return b.build();
    }


    enum State {
        IDLE,
        PRE_PROCESSING,
        WAITING_FOR_TAG, // aka Protocol activation
        SELECTION,
        KERNEL_PROCESSING,
        ENDED
    }
}
