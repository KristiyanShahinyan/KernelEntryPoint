package digital.paynetics.phos.entry_point;

public class InvalidConfigurationException extends Exception {
    public InvalidConfigurationException() {
    }


    public InvalidConfigurationException(final String message) {
        super(message);
    }


    public InvalidConfigurationException(final String message, final Throwable cause) {
        super(message, cause);
    }


    public InvalidConfigurationException(final Throwable cause) {
        super(cause);
    }
}
