import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.io.IOException;

public class GUDPSocket implements GUDPSocketAPI  {
    DatagramSocket datagramSocket;
    
    private LinkedList<GUDPEndPoint> sendQueue = new LinkedList<>();
    private LinkedList<GUDPEndPoint> receiveQueue = new LinkedList<>();
    
    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        addEndpointToSendQueue(packetEndPoint);
        
        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        DatagramPacket udppacket = gudppacket.pack();
        datagramSocket.send(udppacket);
    }
    
    public void receive(DatagramPacket packet) throws IOException {
        byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        datagramSocket.receive(udppacket);
        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        gudppacket.decapsulate(packet);

        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        addEndpointToReceiveQueue(packetEndPoint);
    }

    public void finish() throws IOException {
        ;
    }
    public void close() throws IOException {
        ;
    }

    private class SenderThread extends Thread {

        public SenderThread() {
        }

        public void run() {
            
        }
    }

    private void addEndpointToSendQueue(GUDPEndPoint endPoint) {
        if (this.sendQueue.contains(endPoint)) return;
        this.sendQueue.add(endPoint);
    }

    private void addEndpointToReceiveQueue(GUDPEndPoint endPoint) {
        if (this.receiveQueue.contains(endPoint)) return;
        this.receiveQueue.add(endPoint);
    }

    private GUDPEndPoint getPacketEndPoint(DatagramPacket packet) {
        InetAddress packetAddress = packet.getAddress();
        int packetPort = packet.getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

}

