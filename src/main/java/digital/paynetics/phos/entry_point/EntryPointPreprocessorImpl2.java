package digital.paynetics.phos.entry_point;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import digital.paynetics.phos.kernel.common.emv.TtqPreProcessing;
import digital.paynetics.phos.kernel.common.emv.entry_point.EntryPointConfiguration;
import digital.paynetics.phos.kernel.common.emv.entry_point.preprocessing.EntryPointPreProcessingIndicators;
import digital.paynetics.phos.kernel.common.emv.entry_point.preprocessing.EntryPointPreprocessor;
import digital.paynetics.phos.kernel.common.emv.kernel.common.KernelType;
import digital.paynetics.phos.kernel.common.emv.kernel.common.TlvMap;
import digital.paynetics.phos.kernel.common.emv.kernel.common.TlvMapImpl;
import digital.paynetics.phos.kernel.common.emv.tag.EmvTag;
import digital.paynetics.phos.kernel.common.emv.tag.Tlv;
import digital.paynetics.phos.kernel.common.emv.tag.TlvException;
import digital.paynetics.phos.kernel.common.misc.CardAppConfiguration;
import digital.paynetics.phos.kernel.common.misc.CardApplication;
import digital.paynetics.phos.kernel.common.misc.Currency;
import digital.paynetics.phos.kernel.common.misc.PreprocessedApplication;
import digital.paynetics.phos.kernel.common.misc.TransactionType;
import digital.paynetics.phos.kernel.common.misc.TtqConfiguration;
import java8.util.Optional;

import static digital.paynetics.phos.kernel.common.misc.PhosMessageFormat.format;


/**
 * 'Start A' as described in EMV book A & B
 * Executes pre-processing as described in Book B (Version 2.6), 3.1.1 Pre-Processing Requirements
 */
public class EntryPointPreprocessorImpl2 implements EntryPointPreprocessor {
    private final org.slf4j.Logger logger = LoggerFactory.getLogger(this.getClass());


    @Inject
    public EntryPointPreprocessorImpl2() {
    }


    @Override
    public List<PreprocessedApplication> preProcess(
            List<CardApplication> apps,
            List<CardAppConfiguration> appConfs,
            int amountAuthorized,
            int amountOther,
            Currency currency,
            TransactionType transactionType
    ) {

        Map<String, CardApplication> appsMap = new HashMap<>();
        for (CardApplication app : apps) {
            appsMap.put(app.applicationId, app);
        }

        if (amountAuthorized < 0) {
            throw new IllegalArgumentException("amountAuthorized < 0");
        }

        if (amountOther < 0) {
            throw new IllegalArgumentException("amountOther < 0");
        }

        if (transactionType != TransactionType.CASHBACK && amountOther > 0) {
            throw new IllegalArgumentException("amountOther > 0 when transaction is: " + transactionType);
        }


        List<PreprocessedApplication> ret = new ArrayList<>();

        for (CardAppConfiguration cac : appConfs) {
            if (cac.getTransactionType() != transactionType) {
                throw new IllegalArgumentException(format("CardAppConfiguration " +
                                "transactionType is {} requested pre-processing is for {}",
                        cac.getTransactionType(), transactionType));
            }

            EntryPointConfiguration epc = extractEntryPointConfiguration(cac.getTlvConfigData());
            if (epc != null) {

                // Req 3.1.1.1
                EntryPointPreProcessingIndicators ind = new EntryPointPreProcessingIndicators();


                // Req 3.1.1.2
                TtqPreProcessing ttq = null;
                if (epc.ttq != null) {
                    ttq = new TtqPreProcessing(epc.ttq);
                }

                // Req 3.1.1.3
                if (epc.statusCheckSupported) {
                    if (amountAuthorized == currency.getSingleUnit()) {
                        ind.raiseStatusCheckRequestedFlag();
                    }
                }

                // Req 3.1.1.4
                if (amountAuthorized == 0) {
                    if (epc.zeroAmountAllowed) {
                        ind.raiseZeroAmountFlag();
                    } else {
                        ind.raiseContactlessApplicationNotAllowedFlag();
                    }
                }

                if (!isMastercardApp(cac)) {
                    // Req 3.1.1.5
                    if (epc.readerContactlessTransactionLimit >= 0 &&
                            amountAuthorized >= epc.readerContactlessTransactionLimit) {

                        ind.raiseContactlessApplicationNotAllowedFlag();
                    }

                    // Req 3.1.1.6
                    if (epc.readerContactlessFloorLimit >= 0 &&
                            amountAuthorized > epc.readerContactlessFloorLimit) {

                        ind.raiseReaderContactlessFloorLimitExceededFlag();
                    }

                    // Req 3.1.1.7
                    if (epc.readerContactlessFloorLimit < 0) {
                        if (epc.terminalFloorLimit >= 0) {
                            if (amountAuthorized > epc.terminalFloorLimit) {
                                ind.raiseReaderContactlessFloorLimitExceededFlag();
                            }
                        }
                    }
                }

                // Req 3.1.1.8
                if (epc.readerCvmRequiredLimit >= 0 &&
                        amountAuthorized >= epc.readerCvmRequiredLimit) {

                    ind.raiseReaderCvmLimitExceededFlag();
                }

                if (ttq != null) {
                    // Req 3.1.1.9
                    if (ind.isReaderContactlessFloorLimitExceeded()) {
                        ttq.raiseOnlineCryptogramRequiredFlag();
                    }

                    // Req 3.1.1.10
                    if (ind.isStatusCheckRequested()) {
                        ttq.raiseOnlineCryptogramRequiredFlag();
                    }

                    // Req 3.1.1.11
                    if (ind.isZeroAmount()) {
                        if (!ttq.isOfflineOnlyReader) {
                            ttq.raiseOnlineCryptogramRequiredFlag();
                        } else {
                            ind.raiseContactlessApplicationNotAllowedFlag();
                        }
                    }

                    // Req 3.1.1.12
                    if (ind.isReaderCvmLimitExceeded()) {
                        ttq.raiseCvmRequiredFlag();
                    }

                    ind.setTtq(ttq);
                }

                // resolving kernel cannot fail because we check parameters when constructing TerminalConfigImpl
                KernelType kernelType = appsMap.get(cac.getApplicationId()).kernelType;

                ret.add(new PreprocessedApplication(cac,
                        ind,
                        Optional.ofNullable(ttq).map(TtqPreProcessing::getFinal),
                        kernelType));
            }
        }

        return ret;
    }


    private boolean isMastercardApp(CardAppConfiguration cac) {
        return cac.getApplicationId().startsWith("A000000004") || cac.getApplicationId().startsWith("B012345678");
    }


    private EntryPointConfiguration extractEntryPointConfiguration(List<Tlv> tlvConfigData) {
        TlvMap tlvMap = new TlvMapImpl(tlvConfigData);

        boolean statusCheckEnabled = false;
        if (tlvMap.isTagPresentAndNonEmpty(EmvTag.PHOS_STATUS_CHECK_ENABLED)) {
            statusCheckEnabled = tlvMap.get(EmvTag.PHOS_STATUS_CHECK_ENABLED).getValueBytes()[0] != 0;
        }

        boolean zeroAmountAllowed = false;
        if (tlvMap.isTagPresentAndNonEmpty(EmvTag.PHOS_ZERO_AMOUNT_ALLOWED)) {
            zeroAmountAllowed = tlvMap.get(EmvTag.PHOS_ZERO_AMOUNT_ALLOWED).getValueBytes()[0] != 0;
        }

        boolean extendedSelectionSupported = false;
        if (tlvMap.isTagPresentAndNonEmpty(EmvTag.PHOS_EXTENDED_SELECTION_SUPPRORTED)) {
            extendedSelectionSupported = tlvMap.get(EmvTag.PHOS_EXTENDED_SELECTION_SUPPRORTED).getValueBytes()[0] != 0;
        }

        int readerContactlessTransactionLimit = -1;
        int readerContactlessTransactionLimitOdCvm = -1;
        int terminalFloorLimit = -1;
        int readerCvmLimit = -1;
        int readerContactlessFloorLimit = -1;


        try {
            if (tlvMap.isTagPresentAndNonEmpty(EmvTag.READER_CONTACTLESS_TRANSACTION_LIMIT_NO_OD_CVM)) {
                readerContactlessTransactionLimit = tlvMap.get(EmvTag.READER_CONTACTLESS_TRANSACTION_LIMIT_NO_OD_CVM).
                        getValueAsBcdInt();
            }

            if (tlvMap.isTagPresentAndNonEmpty(EmvTag.READER_CONTACTLESS_TRANSACTION_LIMIT_OD_CVM)) {
                readerContactlessTransactionLimitOdCvm = tlvMap.get(EmvTag.READER_CONTACTLESS_TRANSACTION_LIMIT_OD_CVM).
                        getValueAsBcdInt();
            }

            if (tlvMap.isTagPresentAndNonEmpty(EmvTag.TERMINAL_FLOOR_LIMIT)) {
                terminalFloorLimit = tlvMap.get(EmvTag.TERMINAL_FLOOR_LIMIT).getValueAsBcdInt();
            }

            if (tlvMap.isTagPresentAndNonEmpty(EmvTag.READER_CVM_REQUIRED_LIMIT)) {
                readerCvmLimit = tlvMap.get(EmvTag.READER_CVM_REQUIRED_LIMIT).getValueAsBcdInt();
            }

            if (tlvMap.isTagPresentAndNonEmpty(EmvTag.READER_CONTACTLESS_FLOOR_LIMIT)) {
                readerContactlessFloorLimit = tlvMap.get(EmvTag.READER_CONTACTLESS_FLOOR_LIMIT).getValueAsBcdInt();
            }

        } catch (TlvException e) {
            logger.error("Cannot extract value: {}", e.getMessage());
            return null;
        }

        TtqConfiguration ttqConfiguration = null;
        if (tlvMap.isTagPresentAndNonEmpty(EmvTag.PHOS_TTQ_CONFIGURATION)) {
            String ttqJson = tlvMap.get(EmvTag.PHOS_TTQ_CONFIGURATION).getValueAsString();
            Gson gson = new Gson();
            try {
                ttqConfiguration = gson.fromJson(ttqJson, TtqConfiguration.class);
            } catch (JsonSyntaxException e) {
                logger.error("{}", ttqJson);
                logger.error("Cannot extract TtqConfiguration: {}", e.getMessage());
                return null;
            }
        }

        int finalReaderContactlessTransactionLimit;
        if (readerContactlessTransactionLimitOdCvm != -1 &&
                readerContactlessTransactionLimitOdCvm > readerContactlessTransactionLimit) {
            finalReaderContactlessTransactionLimit = readerContactlessTransactionLimitOdCvm;
        } else {
            finalReaderContactlessTransactionLimit = readerContactlessTransactionLimit;
        }


        return new EntryPointConfiguration(statusCheckEnabled,
                zeroAmountAllowed,
                finalReaderContactlessTransactionLimit,
                readerContactlessFloorLimit,
                terminalFloorLimit,
                readerCvmLimit,
                extendedSelectionSupported,
                ttqConfiguration
        );
    }
}
