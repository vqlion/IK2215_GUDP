import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Random;
import java.io.IOException;

public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;

    private LinkedList<GUDPEndPoint> sendQueue = new LinkedList<>();
    private LinkedList<GUDPEndPoint> receiveQueue = new LinkedList<>();

    private SenderThread senderThread = new SenderThread();

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        int endPointQueueIndex = addEndpointToSendQueue(packetEndPoint);

        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);

        if (!this.senderThread.isAlive()) {
            senderThread.start();
        }

        synchronized (this.sendQueue) {
            GUDPEndPoint endPoint = this.sendQueue.get(endPointQueueIndex);
            int last = endPoint.getLast();
            gudppacket.setSeqno(last + 1);
            endPoint.setLast(last + 1);

            this.sendQueue.get(endPointQueueIndex).add(gudppacket);
            this.sendQueue.notifyAll();

            // System.out.println("Added packet seq nbr " + (last + 1) + " to endpoint " +
            // endPoint);
        }

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

    private int addEndpointToSendQueue(GUDPEndPoint endPoint) throws IOException {
        synchronized (this.sendQueue) {

            for (GUDPEndPoint queuedEndPoint : this.sendQueue) {
                if (endPoint.getRemoteEndPoint().getAddress() == queuedEndPoint.getRemoteEndPoint().getAddress()
                        && endPoint.getRemoteEndPoint().getPort() == queuedEndPoint.getRemoteEndPoint().getPort()) {
                    return this.sendQueue.indexOf(queuedEndPoint);
                }
            }
            Random rand = new Random();
            int endPointBSN = rand.nextInt();

            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

            endPoint.setBase(endPointBSN);
            endPoint.setNextseqnum(endPointBSN);

            byte[] buffer = new byte[0];
            DatagramPacket BSNPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

            GUDPPacket gudpPacket = GUDPPacket.encapsulate(BSNPacket);
            gudpPacket.setType(GUDPPacket.TYPE_BSN);
            gudpPacket.setSeqno(endPointBSN);

            endPoint.add(gudpPacket);
            endPoint.setLast(endPointBSN);

            this.sendQueue.add(endPoint);
            return this.sendQueue.indexOf(endPoint);
        }
    }

    private GUDPEndPoint getPacketEndPoint(DatagramPacket packet) {
        InetAddress packetAddress = packet.getAddress();
        int packetPort = packet.getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

    private boolean messagesInSocketQueue(LinkedList<GUDPEndPoint> queue) {
        for (GUDPEndPoint gudpEndPoint : queue) {
            if (!gudpEndPoint.isEmptyBuffer())
                return true;
        }
        return false;
    }

    /*
     * Main just meant for testing
     */
    public static void main(String args[]) throws IOException {
        InetAddress address = InetAddress.getLoopbackAddress();
        InetAddress address2 = InetAddress.getByName("192.168.1.130");
        System.out.println(address);
        System.out.println(address2);
        GUDPSocket gudpSocket = new GUDPSocket(new DatagramSocket());

        byte[] buffer = new byte[10];
        DatagramPacket packet = new DatagramPacket(buffer, 0, 10, address, 2220);
        DatagramPacket packet2 = new DatagramPacket(buffer, 0, 10, address2, 2220);

        gudpSocket.send(packet2);
        gudpSocket.send(packet2);
        gudpSocket.send(packet2);
        gudpSocket.send(packet2);
        // gudpSocket.send(packet2);

    }

    private class SenderThread extends Thread {

        public SenderThread() {
        }

        public void run() {
            while (true) {

                synchronized (GUDPSocket.this.sendQueue) {
                    while (GUDPSocket.this.sendQueue.size() == 0 || !messagesInSocketQueue(GUDPSocket.this.sendQueue)) {
                        try {
                            System.out.println(">>Sender Thread waiting...");
                            GUDPSocket.this.sendQueue.wait();
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                            System.err.println("Sender Thread interrupted " + e);
                        }
                    }

                    for (GUDPEndPoint endPoint : GUDPSocket.this.sendQueue) {
                        try {
                            handleGUDPEndpoint(endPoint);
                        } catch (Exception e) {
                            System.err.println("Error sending packet on enpoint" + endPoint + " error: " + e);
                        }
                    }
                }
            }
        }

        private void handleGUDPEndpoint(GUDPEndPoint endPoint) throws IOException {
            GUDPEndPoint.endPointState state = endPoint.getState();
            GUDPEndPoint.readyEvent event = endPoint.getEvent();

            if (endPoint.isEmptyBuffer())
                return;

            System.out.println(">>Sender Thread handling endpoint " + endPoint);

            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

            if (state == GUDPEndPoint.endPointState.INIT) {
                int endPointBSN = endPoint.getBase();

                GUDPPacket gudpPacket = endPoint.getPacket(endPointBSN);
                if (gudpPacket != null) {
                    DatagramPacket udpPacket = gudpPacket.pack();

                    GUDPSocket.this.datagramSocket.send(udpPacket);
                    System.out.println(GUDPSocket.bytesToHex(gudpPacket.getBytes()));
                    
                    // GUDPPacket firstDataPacket = endPoint.remove();
                    // endPoint.add(gudpPacket);
                    // endPoint.add(firstDataPacket);
                    
                    endPoint.setNextseqnum(endPointBSN + 1);
                    
                    endPoint.setState(GUDPEndPoint.endPointState.BSN);
                    endPoint.setEvent(GUDPEndPoint.readyEvent.SEND);
                    
                    // FOR TESTING ONLY
                    // endPoint.removeAll();
                }

            } else if (state == GUDPEndPoint.endPointState.BSN) {
                if (event == GUDPEndPoint.readyEvent.SEND) {

                    int windowSize = endPoint.getWindowSize();
                    int base = endPoint.getBase();
                    
                    for (int i = base; i < base + windowSize; i++) {
                        int nextSeq = endPoint.getNextseqnum();
                        if (i < nextSeq)
                            continue;

                        GUDPPacket packet = endPoint.getPacket(i);
                        if (packet == null || packet.getSeqno() != nextSeq)
                            continue;

                        GUDPSocket.this.datagramSocket.send(packet.pack());
                        System.out.println(GUDPSocket.bytesToHex(packet.getBytes()));
                        endPoint.setNextseqnum(nextSeq + 1);
                    }

                    endPoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                    endPoint.removeAll();

                }
            }

            System.out.println(
                    "   Endpoint processed " + state + " state " + endPoint.getRemoteEndPoint() + " -BSN- "
                            + endPoint.getBase()
                            + " -nextSeq- " + endPoint.getNextseqnum());
        }
    }

    // TESTING: print packets data

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

}
