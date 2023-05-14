package digital.paynetics.phos.entry_point;


import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import digital.paynetics.phos.kernel.common.emv.cert.CaRidDbReadOnly;
import digital.paynetics.phos.kernel.common.emv.cert.CertificateRevocationListReadOnly;
import digital.paynetics.phos.kernel.common.misc.CardAppConfiguration;
import digital.paynetics.phos.kernel.common.misc.CardApplication;
import digital.paynetics.phos.kernel.common.misc.TransactionType;



/**
 * PosChangeableConfiguration contains fields that might be changed at some point in time.
 *
 * @see PosPermanentConfiguration
 */
public final class PosChangeableConfiguration {
    @SerializedName("supported_transaction_types")
    private final List<TransactionType> supportedTransactionTypes;

    @SerializedName("card_applications")
    private List<CardApplication> cardApplications;

    @SerializedName("card_application_configurations")
    private List<CardAppConfiguration> cardAppConfigurations;

    private final CaRidDbReadOnly caRidDb;

    private final CertificateRevocationListReadOnly crl;


    private final Map<TransactionType, List<CardAppConfiguration>> transactionAppConfigs;


    public PosChangeableConfiguration(List<TransactionType> supportedTransactionTypes,
                                      List<CardApplication> cardApplications,
                                      List<CardAppConfiguration> cardAppConfigurations,
                                      CaRidDbReadOnly caRidDb,
                                      CertificateRevocationListReadOnly crl,
                                      Map<TransactionType, List<CardAppConfiguration>> transactionAppConfigs) {

        this.transactionAppConfigs = transactionAppConfigs;

        if (!checkValidParams(supportedTransactionTypes, cardApplications, cardAppConfigurations)) {
            throw new IllegalArgumentException();
        }

        this.supportedTransactionTypes = supportedTransactionTypes;
        this.cardApplications = cardApplications;
        this.cardAppConfigurations = cardAppConfigurations;
        this.caRidDb = caRidDb;
        this.crl = crl;


    }


    public CaRidDbReadOnly getCaRidDb() {
        return caRidDb;
    }


    public static boolean checkValidParams(List<TransactionType> supportedTransactionTypes,
                                           List<CardApplication> cardApplications,
                                           List<CardAppConfiguration> cardAppConfigurations) {

        final Set<String> caIds = new HashSet<>();
        for (final CardApplication ca : cardApplications) {
            if (caIds.contains(ca.applicationId)) {
                return false;
            }

            caIds.add(ca.applicationId);
        }

        for (final CardAppConfiguration cac : cardAppConfigurations) {
            if (!supportedTransactionTypes.contains(cac.getTransactionType())) {
                return false;
            }

            if (!caIds.contains(cac.getApplicationId())) {
                return false;
            }
        }

        return true;
    }


    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }

        if (!(obj instanceof PosChangeableConfiguration)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final PosChangeableConfiguration other = (PosChangeableConfiguration) obj;

        return  supportedTransactionTypes.equals(other.supportedTransactionTypes) &&
                cardApplications.equals(other.cardApplications) &&
                cardAppConfigurations.equals(other.cardAppConfigurations);

    }


    @Override
    public int hashCode() {
        return Objects.hash(supportedTransactionTypes, cardApplications, cardAppConfigurations);
    }


    @Override
    public String toString() {
        return "SUPPORTED_TRANSACTION_TYPES: " + supportedTransactionTypes;
    }


    public List<TransactionType> getSupportedTransactionTypes() {
        return Collections.unmodifiableList(supportedTransactionTypes);
    }


    public List<CardApplication> getCardApplications() {
        return Collections.unmodifiableList(cardApplications);
    }


    public List<CardAppConfiguration> getCardAppConfigurations() {
        return Collections.unmodifiableList(cardAppConfigurations);
    }


    public List<CardAppConfiguration> getAppConfigurations(TransactionType tt) {
        final List<CardAppConfiguration> ret = transactionAppConfigs.get(tt);
        if (ret == null) {
            throw new IllegalArgumentException("No such TransactionType in transactionAppConfigs: " + tt);
        }

        return ret;
    }


//    private void computeTransactionAppConfigs(List<TransactionType> supportedTransactionTypes,
//                                              List<CardAppConfiguration> cardAppConfigurations) {
//
//        for (final TransactionType tt : supportedTransactionTypes) {
//            transactionAppConfigs.put(tt, new ArrayList<>());
//        }
//
//        for (final CardAppConfiguration cac : cardAppConfigurations) {
//            final List<CardAppConfiguration> list = transactionAppConfigs.get(cac.transactionType);
//            list.add(cac);
//        }
//    }

    public CertificateRevocationListReadOnly getCrl() {
        return crl;
    }
}
