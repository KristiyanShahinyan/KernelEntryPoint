package digital.paynetics.phos.entry_point;


import digital.paynetics.phos.kernel.common.misc.CountryCode;
import digital.paynetics.phos.kernel.common.misc.Currency;
import digital.paynetics.phos.kernel.common.misc.TerminalCapabilities13;
import digital.paynetics.phos.kernel.common.misc.TerminalType;


/**
 * PosPermanentConfiguration is supposed to be set only once per app install and not changed in any circumstances
 *
 * @see PosChangeableConfiguration
 */
public final class PosPermanentConfiguration {
    private final TerminalType terminalType;
    private final CountryCode countryCode;
    private final Currency currency;
    private final TerminalCapabilities13 terminalCapabilities13;


    public PosPermanentConfiguration(
            TerminalType terminalType,
            final CountryCode countryCode,
            final Currency currency,
            TerminalCapabilities13 terminalCapabilities13) {
        this.terminalType = terminalType;

        this.countryCode = countryCode;
        this.currency = currency;
        this.terminalCapabilities13 = terminalCapabilities13;
    }


    public TerminalType getTerminalType() {
        return terminalType;
    }


    public CountryCode getCountryCode() {
        return countryCode;
    }


    public Currency getCurrency() {
        return currency;
    }


    public TerminalCapabilities13 getTerminalCapabilities13() {
        return terminalCapabilities13;
    }
}
