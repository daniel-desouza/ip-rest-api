package api;

public class IPAddress {

    private final String address;
    private final String status;

    public IPAddress(String address, String status) {
        this.address = address;
        this.status = status;
    }

    public String getAddress() {
        return address;
    }

    public String getStatus() {
        return status;
    }

	@Override
	public String toString() {
		return "IPAddress [address=" + address + ", status=" + status + "]";
	}
    
}
