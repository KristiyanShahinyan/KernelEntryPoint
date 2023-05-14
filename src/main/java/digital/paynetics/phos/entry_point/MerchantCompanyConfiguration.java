package digital.paynetics.phos.entry_point;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

import digital.paynetics.phos.kernel.common.misc.CountryCode;


/**
 * Represents the company, i.e. legal entity of the merchant
 */
public final class MerchantCompanyConfiguration {
    /**
     * Company's registration number. Usually the tax number
     */
    @SerializedName("company_state_id")
    public final String companyStateId;
    /**
     * Company name
     */
    @SerializedName("name")
    public final String name;
    /**
     * "Doing business as" name
     */
    @SerializedName("doing_business_as")
    public final String doingBusinessAs;
    /**
     * Country where the company is registered
     */
    @SerializedName("country")
    public final CountryCode country;
    /**
     * City of registration
     */
    @SerializedName("city")
    public final String city;
    /**
     * Address of registration
     */
    @SerializedName("address")
    public final String address;
    /**
     * Postal code of registration
     */
    @SerializedName("postal_code")
    public final String postalCode;


    public MerchantCompanyConfiguration(final String companyStateId,
                                        final String name,
                                        final String doingBusinessAs,
                                        final CountryCode country,
                                        final String city,
                                        final String address,
                                        final String postalCode) {

        this.companyStateId = companyStateId;
        this.name = name;
        this.doingBusinessAs = doingBusinessAs;
        this.country = country;
        this.city = city;
        this.address = address;
        this.postalCode = postalCode;
    }


    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        if (!(obj instanceof MerchantCompanyConfiguration)) {
            return false;
        }


        final MerchantCompanyConfiguration other = (MerchantCompanyConfiguration) obj;


        return companyStateId.equals(other.companyStateId) &&
                name.equals(other.name) &&
                doingBusinessAs.equals(other.doingBusinessAs) &&
                country == other.country &&
                city.equals(other.city) &&
                address.equals(other.address) &&
                postalCode.equals(other.postalCode);
    }


    @Override
    public int hashCode() {
        return Objects.hash(companyStateId, name, doingBusinessAs, country, city, address, postalCode);
    }


    @Override
    public String toString() {
        return "companyStateId: " + companyStateId +
                ", name: " + name +
                ", doingBusinessAs: " + doingBusinessAs +
                ", country: " + country +
                ", city: " + city +
                ", address: " + address +
                ", postalCode: " + postalCode;
    }
}
