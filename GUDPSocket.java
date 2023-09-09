import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Random;
import java.io.IOException;

public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;

    private LinkedList<GUDPEndPoint> sendQueue = new LinkedList<>();
    private LinkedList<GUDPEndPoint> receiveQueue = new LinkedList<>();

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        int endPointQueueIndex = addEndpointToSendQueue(packetEndPoint);

        System.out.println(this.sendQueue);
        System.out.println(endPointQueueIndex);

        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        DatagramPacket udppacket = gudppacket.pack();

        this.sendQueue.get(endPointQueueIndex).add(gudppacket);

        // datagramSocket.send(udppacket);
    }

    public void receive(DatagramPacket packet) throws IOException {
        byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        datagramSocket.receive(udppacket);
        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        gudppacket.decapsulate(packet);
    }

    public void finish() throws IOException {
        ;
    }

    public void close() throws IOException {
        ;
    }
    // TODO: implement SenderThread startup and termination and test it
    private class SenderThread extends Thread {

        public SenderThread() {
        }

        public void run() {
            while (true) {

                while (getSendQueueSize() == 0) {
                    try {
                        GUDPSocket.this.sendQueue.wait();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                        System.err.println("Sender Thread interrupted");
                    }
                }

                for (GUDPEndPoint endPoint : sendQueue) {
                    try {
                        handleGUDPEndpoint(endPoint);
                    } catch (Exception e) {
                        System.err.println("Error sending packet on enpoint" + endPoint);
                    }
                }
            }
        }

        // TODO: test function handleGUDPEnpoint
        private void handleGUDPEndpoint(GUDPEndPoint endPoint) throws IOException {
            GUDPEndPoint.endPointState state = endPoint.getState();
            if (state == GUDPEndPoint.endPointState.INIT) {
                Random rand = new Random();
                int endPointBSN = rand.nextInt();

                InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

                endPoint.setBase(endPointBSN);
                endPoint.setExpectedseqnum(endPointBSN + 1);
                endPoint.setNextseqnum(endPointBSN + 1);
                endPoint.setLast(endPointBSN + 1);

                byte[] buffer = new byte[0];
                DatagramPacket BSNPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

                GUDPPacket gudpPacket = GUDPPacket.encapsulate(BSNPacket);
                gudpPacket.setType(GUDPPacket.TYPE_BSN);
                gudpPacket.setSeqno(endPointBSN);
                DatagramPacket udpPacket = gudpPacket.pack();
            }
        }
    }

    private int addEndpointToSendQueue(GUDPEndPoint endPoint) {
        for (GUDPEndPoint queuedEndPoint : this.sendQueue) {
            if (endPoint.getRemoteEndPoint().getAddress() == queuedEndPoint.getRemoteEndPoint().getAddress()
                    && endPoint.getRemoteEndPoint().getPort() == queuedEndPoint.getRemoteEndPoint().getPort()) {
                return this.sendQueue.indexOf(queuedEndPoint);
            }
        }
        this.sendQueue.add(endPoint);
        return this.sendQueue.indexOf(endPoint);
    }

    private GUDPEndPoint getPacketEndPoint(DatagramPacket packet) {
        InetAddress packetAddress = packet.getAddress();
        int packetPort = packet.getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

    private int getSendQueueSize() {
        return this.sendQueue.size();
    }

    /*
     * Main just meant for testing
     */
    public static void main(String args[]) throws IOException {
        InetAddress address = InetAddress.getLoopbackAddress();
        InetAddress address2 = InetAddress.getByName("192.168.1.130");
        System.out.println(address);
        System.out.println(address2);
        GUDPSocket gudpSocket = new GUDPSocket(new DatagramSocket(0, address));

        byte[] buffer = new byte[10];
        DatagramPacket packet = new DatagramPacket(buffer, 0, 10, address, 0);
        DatagramPacket packet2 = new DatagramPacket(buffer, 0, 10, address2, 0);

        gudpSocket.send(packet);
        gudpSocket.send(packet2);
    }

}
