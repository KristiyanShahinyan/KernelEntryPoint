package digital.paynetics.phos.entry_point;

import java.util.List;

import digital.paynetics.phos.kernel.mastercard.torn.TornTransactionLogRecord;


public interface TornTransactionLogPersister {
    List<TornTransactionLogRecord> load();

    void save(List<TornTransactionLogRecord> logRecords);

    void clear();
}
