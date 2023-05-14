package digital.paynetics.phos.entry_point;

import java.util.Objects;


/**
 * This class represents the configuration of a single POS terminal from merchant's point of view
 */
public class MerchantTerminalConfiguration {
    /**
     * This ID is assigned by the merchant at his discretion. Optional. Usually empty for merchants with only one
     * terminal.
     */
    public final String merchantTerminalId;
    /**
     * Shop name. Optional. Usually empty for merchants with only one terminal.
     */
    public final String shopName;
    /**
     * City/town of the shop's location. This field might be needed in some countries/markets where the receipt must
     * contain not only the company's address but also the shop's one
     */
    public final String city;
    /**
     * Street address of the shop
     */
    public final String address;


    public MerchantTerminalConfiguration(final String merchantTerminalId, final String shopName, final String city, final String address) {
        this.merchantTerminalId = merchantTerminalId;
        this.shopName = shopName;
        this.city = city;
        this.address = address;
    }


    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof MerchantTerminalConfiguration)) {
            return false;
        }


        final MerchantTerminalConfiguration other = (MerchantTerminalConfiguration) obj;


        return merchantTerminalId.equals(other.merchantTerminalId) &&
                shopName.equals(other.shopName) &&
                city.equals(other.city) &&
                address.equals(other.address);
    }


    @Override
    public int hashCode() {
        return Objects.hash(merchantTerminalId, shopName, city, address);
    }


    @Override
    public String toString() {
        return "merchantTerminalId: " + merchantTerminalId +
                ", shopName: " + shopName +
                ", city: " + city +
                ", address: " + address;

    }
}
