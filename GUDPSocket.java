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
    private boolean senderThreadRunning;

    private ReceiverThread receiverThread = new ReceiverThread();
    private boolean receiverThreadRunning;

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
        senderThreadRunning = true;
        receiverThreadRunning = true;
    }

    public int getPort() {
        return this.datagramSocket.getLocalPort();
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        int endPointQueueIndex = getEnpointSendQueueIndex(packetEndPoint);

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
        // byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        // DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        // datagramSocket.receive(udppacket);
        // GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        // gudppacket.decapsulate(packet);

        if (!this.receiverThread.isAlive())
            receiverThread.start();

    }

    public void finish() throws IOException {
        ;
    }

    public void close() throws IOException {
        System.out.println("------------");
        System.out.println("APP: Closing socket " + this.datagramSocket.getLocalSocketAddress());
        this.senderThreadRunning = false;
        this.senderThread.interrupt();
        clearSendQueue();
        if (!this.datagramSocket.isClosed())
            this.datagramSocket.close();
    }

    private void clearSendQueue() {
        for (GUDPEndPoint endPoint : this.sendQueue) {
            endPoint.removeAll();
            endPoint.stopTimer();
            endPoint.setState(GUDPEndPoint.endPointState.CLOSED);
        }

        this.sendQueue.clear();
    }

    private int getEnpointSendQueueIndex(GUDPEndPoint endPoint) throws IOException {
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

    private GUDPEndPoint getPacketEndPoint(GUDPPacket packet) {
        InetAddress packetAddress = packet.getSocketAddress().getAddress();
        int packetPort = packet.getSocketAddress().getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

    private boolean messagesInSocketQueue(LinkedList<GUDPEndPoint> queue) {
        for (GUDPEndPoint gudpEndPoint : queue) {
            if (!gudpEndPoint.isEmptyBuffer())
                return true;
        }
        return false;
    }

    private class SenderThread extends Thread {

        public SenderThread() {
        }

        public void run() {
            while (GUDPSocket.this.senderThreadRunning) {

                synchronized (GUDPSocket.this.sendQueue) {
                    while (GUDPSocket.this.sendQueue.size() == 0 || !messagesInSocketQueue(GUDPSocket.this.sendQueue)) {
                        try {
                            System.out.println(">>Sender Thread waiting...");
                            GUDPSocket.this.sendQueue.wait();
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                            System.err.println(">>Sender Thread interrupted " + e);
                        }
                    }

                    for (GUDPEndPoint endPoint : GUDPSocket.this.sendQueue) {
                        try {
                            handleGUDPEndpoint(endPoint);
                        } catch (Exception e) {
                            System.err.println(">>Sender Thread: Error sending packet on enpoint"
                                    + endPoint.getRemoteEndPoint() + ". error: " + e);
                        }
                    }
                }
            }
        }

        private void handleGUDPEndpoint(GUDPEndPoint endPoint) throws IOException {
            GUDPEndPoint.endPointState state = endPoint.getState();
            GUDPEndPoint.readyEvent event = endPoint.getEvent();

            if (event == GUDPEndPoint.readyEvent.WAIT)
                return;

            System.out.println();
            System.out.println(">>Sender Thread handling endpoint " + endPoint.getRemoteEndPoint());
            System.out.println("--Data of packets sent--");

            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

            if (state == GUDPEndPoint.endPointState.MAXRETRIED) {
                endPoint.removeAll();
                endPoint.setState(GUDPEndPoint.endPointState.FINISHED);
                throw new IOException("Endpoint " + endPointSocketAddress + " reached max retry.");
            }

            if (state == GUDPEndPoint.endPointState.FINISHED) {
                if (endPoint.isEmptyBuffer()) {

                }
            }

            if (event == GUDPEndPoint.readyEvent.INIT) {
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

            } else if (event == GUDPEndPoint.readyEvent.SEND) {
                if (state == GUDPEndPoint.endPointState.BSN || state == GUDPEndPoint.endPointState.READY) {
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

                    endPoint.startTimer();
                    endPoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                    // endPoint.removeAll();

                }
            } else if (event == GUDPEndPoint.readyEvent.TIMEOUT) {
                int windowSize = endPoint.getWindowSize();
                int base = endPoint.getBase();
                int retry = endPoint.getRetry();
                int maxRetry = endPoint.getMaxRetry();

                if (retry <= maxRetry) {
                    for (int i = base; i < base + windowSize; i++) {
                        GUDPPacket packet = endPoint.getPacket(i);
                        if (packet == null)
                            continue;

                        GUDPSocket.this.datagramSocket.send(packet.pack());
                        System.out.println(GUDPSocket.bytesToHex(packet.getBytes()));
                    }

                    endPoint.setRetry(retry + 1);
                    endPoint.startTimer();
                    endPoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                } else {
                    endPoint.setState(GUDPEndPoint.endPointState.MAXRETRIED);
                }

            }
            System.out.println("--Data of packets sent--");

            System.out.println(
                    "  Endpoint " + endPoint.getRemoteEndPoint() + " processed \n   state: " + state + "\n   NextSeq: "
                            + endPoint.getNextseqnum()
                            + "\n   LastSeq: " + endPoint.getLast()
                            + "\n   Retry: " + endPoint.getRetry());
        }
    }

    private class ReceiverThread extends Thread {
        public ReceiverThread() {
        }

        public void run() {
            while (GUDPSocket.this.receiverThreadRunning) {
                GUDPPacket receivedPacket;
                try {
                    System.out.println(">>Receiver Thread: waiting for packet...");
                    receivedPacket = receivePacket();
                    handleGUDPPacket(receivedPacket);
                } catch (Exception e) {
                    System.err.println(">>Receiver Thread: error receiving packet on socket "
                            + GUDPSocket.this.datagramSocket.getLocalSocketAddress());
                }

            }
        }

        private GUDPPacket receivePacket() throws IOException {

            byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
            DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
            datagramSocket.receive(udppacket);
            GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);

            return gudppacket;
        }

        private void handleGUDPPacket(GUDPPacket packet) throws IOException {
            short type = packet.getType();
            int sequenceNumber = packet.getSeqno();
            GUDPEndPoint packetEndPoint = GUDPSocket.this.getPacketEndPoint(packet);
            int endPointIndex = getEndpointReceiveQueueIndex(packetEndPoint);

            if (type == GUDPPacket.TYPE_BSN) {
                packetEndPoint.setExpectedseqnum(sequenceNumber);
                sendAck(packetEndPoint);
            } else if (type == GUDPPacket.TYPE_DATA) {
                packetEndPoint = GUDPSocket.this.receiveQueue.get(endPointIndex);

            } else if (type == GUDPPacket.TYPE_ACK) {
                endPointIndex = GUDPSocket.this.getEnpointSendQueueIndex(packetEndPoint);
                packetEndPoint = GUDPSocket.this.sendQueue.get(endPointIndex);

                
                
            }
        }

        private int getEndpointReceiveQueueIndex(GUDPEndPoint endPoint) {
            synchronized (GUDPSocket.this.receiveQueue) {
                for (GUDPEndPoint queuedEndPoint : GUDPSocket.this.receiveQueue) {
                    if (endPoint.getRemoteEndPoint().getAddress() == queuedEndPoint.getRemoteEndPoint().getAddress()
                            && endPoint.getRemoteEndPoint().getPort() == queuedEndPoint.getRemoteEndPoint().getPort()) {
                        return GUDPSocket.this.receiveQueue.indexOf(queuedEndPoint);
                    }
                }
                GUDPSocket.this.receiveQueue.add(endPoint);
                return GUDPSocket.this.receiveQueue.indexOf(endPoint);
            }
        }

        private void sendAck(GUDPEndPoint endPoint) throws IOException {
            int expectedSequenceNum = endPoint.getExpectedseqnum();
            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();
            byte[] buffer = new byte[0];

            DatagramPacket ACKPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

            GUDPPacket gudpPacket = GUDPPacket.encapsulate(ACKPacket);

            gudpPacket.setType(GUDPPacket.TYPE_ACK);
            gudpPacket.setSeqno(expectedSequenceNum + 1);

            endPoint.setExpectedseqnum(expectedSequenceNum + 1);

            DatagramPacket udpPacket = gudpPacket.pack();

            GUDPSocket.this.datagramSocket.send(udpPacket);
            System.out.println(GUDPSocket.bytesToHex(gudpPacket.getBytes()));
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
