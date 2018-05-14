package ezspToWiresharkSniffer;

import java.io.File;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.rmi.UnknownHostException;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.dongle.ember.ZigBeeDongleEzsp;
import com.zsmartsystems.zigbee.dongle.ember.internal.EzspFrameHandler;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.EzspFrame;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.command.EzspMfglibRxHandler;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.command.EzspMfglibSetChannelRequest;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.command.EzspMfglibSetChannelResponse;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.command.EzspMfglibStartRequest;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.command.EzspMfglibStartResponse;
import com.zsmartsystems.zigbee.dongle.ember.internal.ezsp.structure.EmberStatus;
import com.zsmartsystems.zigbee.serial.ZigBeeSerialPort;
import com.zsmartsystems.zigbee.transport.ZigBeePort;
import com.zsmartsystems.zigbee.transport.ZigBeePort.FlowControl;

public class ezspMainSniffer {

    /**
     * The {@link org.slf4j.Logger}.
     */
    private final static org.slf4j.Logger logger = LoggerFactory.getLogger(ezspMainSniffer.class);
    
    private static boolean captureEnable = false;
    private static File file;
    private static DatagramSocket client;
    private static InetAddress adresse;
    
    
//----------------------
    
 // FRAME ==>  ETH II, IP, UDP, ZEP, 802.15.4

    private final static byte INDEXZEP_VERSION                = 2;      // index of the field Version [ZEP]
    private final static byte INDEXZEP_TYPE                   = 3;      // index of the field Type [ZEP]
    private final static byte INDEXZEP_CHANNELID              = 4;      // index of the field Channel ID [ZEP]
    private final static byte INDEXZEP_DEVICEID               = 5;      // index of the field Device ID [ZEP]
    private final static byte INDEXZEP_CRCLQIMODE             = 7;      // index of the field CRC/LQI Mode [ZEP]
    private final static byte INDEXZEP_LQIVAL                 = 8;      // index of the field LQI Val [ZEP]
    private final static byte INDEXZEP_NTPTIMESTAMP           = 9;      // index of the field NTP Timestamp [ZEP]
    private final static byte INDEXZEP_SEQUENCE               = 17;      // index of the field Sequence [ZEP]
    private final static byte INDEXZEP_RESERVED               = 21;      // index of the field Reserved [ZEP]
    private final static byte INDEXZEP_LENGTH                 = 31;      // index of the field Length [ZEP]

    private final static byte ZEP_PACKETSIZE               = 32;      // Total size of UDP and ZEP packets


    private final static byte[] ZepEncaps= {
	    // ============================================================================ ZEP[32] (ZEP version:2 type:1 Data)
	    0x45, 0x58,                                                 		// [0,1] Preamble "EX"
	    0x02,                                                       		// [2,2] Version
	    0x01,                                                       		// [3,3] Type
	    0x11,                                                       		// [4,4] Channel ID
	    0x00, 0x01,                                                 		// [5,6] Device ID
	    0x00,                                                       		// [7,7] CRC/LQI Mode   (LQI = 0x00)
	    (byte)0xff,                                                       	// [8,8] LQI Val
	    0x00, 0x0c, (byte)0xd1, 0x36, (byte)0x8b, (byte)0xbd, 0x27, 0x3d,   // [9,16] NTP Timestamp
	    0x00, 0x05, (byte)0xc6, 0x39,                                     	// [17,20] Sequence
	    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 		// [21,30] Reserved
	    0x00,                                                       		// [31,31] Length
    };
    
    
    
    private static EzspFrameHandler ezspListener = new EzspFrameHandler() {

		@Override
		public void handlePacket(EzspFrame response) {
			if( captureEnable ) {
				if(response instanceof EzspMfglibRxHandler) {
					int[] msg = ((EzspMfglibRxHandler)response).getPacketContents();
					
					byte[] out = new byte[ZEP_PACKETSIZE + msg.length];
					
					for(int loop=0; loop<ZEP_PACKETSIZE; loop++) {
						out[loop] = ZepEncaps[loop];
					}	
					
					// modify some value inside header
					
					// channel
					out[INDEXZEP_CHANNELID] = 11;
					
				    // Device ID
					out[INDEXZEP_DEVICEID]= 0x00;
					out[INDEXZEP_DEVICEID+1]= 0x00;					

					// lqi
					out[INDEXZEP_LQIVAL] = (byte)((EzspMfglibRxHandler)response).getLinkQuality();
					
				    // ZEP length
					out[INDEXZEP_LENGTH]= (byte)(msg.length);

					// Data 802.15.4
					for(int loop=0; loop<msg.length; loop++) {
						out[ZEP_PACKETSIZE+loop] = (byte)msg[loop];
					}
					
					// patch FCS to be compatible with CC24xx
					out[ZEP_PACKETSIZE+msg.length-1] = (byte)0x80;
					out[ZEP_PACKETSIZE+msg.length-2] = (byte)0x00;
					
					// send to localhost
		            try {
		                //On crée notre datagramme
		                DatagramPacket packet = new DatagramPacket(out, out.length, adresse, 17754);
		                
		                //On envoie au serveur
		                client.send(packet);
		                logger.debug("Send !!");
		                
		             } catch (SocketException e) {
		                e.printStackTrace();
		             } catch (UnknownHostException e) {
		                e.printStackTrace();
		             } catch (IOException e) {
		                e.printStackTrace();
		             }
					
				}
			}
		}

		@Override
		public void handleLinkStateChange(boolean state) {
			// TODO Auto-generated method stub
			
		}
    	
    };    
		
	
    /**
     * The main method.
     *
     * @param args the command arguments
     */
    public static void main(final String[] args) {
    	
    	Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			
			@Override
			public void run() {
				captureEnable = false;
    	        try {
    				Thread.sleep(1000);
    			} catch (InterruptedException e) {
    				// TODO Auto-generated catch block
    				e.printStackTrace();
    			}
			}
		}));    	
        
        final String serialPortName = "/dev/ttyUSB0";
        final int serialBaud = 57600;
        final FlowControl flowControl = FlowControl.FLOWCONTROL_OUT_XONOFF;

        logger.info("Create Serial Port");
        final ZigBeePort serialPort = new ZigBeeSerialPort(serialPortName, serialBaud, flowControl);

        logger.info("Create ezsp dongle");
        final ZigBeeDongleEzsp dongle = new ZigBeeDongleEzsp(serialPort);

        logger.info("ASH Init");
        if( dongle.initializeEzspProtocol() ) {
        	
        	/** @todo for testing initialization of mfgLib */
            
        	// add listener for receive alla ezsp response and handler
        	dongle.addListener( ezspListener );
        	
        	// start MfgLib
        	EzspMfglibStartRequest mfgStartRqst = new EzspMfglibStartRequest();
        	mfgStartRqst.setRxCallback(true);
        	EzspMfglibStartResponse mfgStartRsp = (EzspMfglibStartResponse) dongle.singleCall(mfgStartRqst, EzspMfglibStartResponse.class);
        	if(EmberStatus.EMBER_SUCCESS == mfgStartRsp.getStatus()) {
            	// set channel
        		EzspMfglibSetChannelRequest mfgSetChannelRqst = new EzspMfglibSetChannelRequest();
        		mfgSetChannelRqst.setChannel(11);
        		EzspMfglibSetChannelResponse mfgSetChannelRsp = (EzspMfglibSetChannelResponse) dongle.singleCall(mfgSetChannelRqst, EzspMfglibSetChannelResponse.class);
        		
        		if(EmberStatus.EMBER_SUCCESS == mfgSetChannelRsp.getStatus() ) {
		            try {
		                // Initialized client part
		                client = new DatagramSocket(17754);
		                adresse = InetAddress.getByName("127.0.0.1");    		                
		                logger.info("Client Udp create !");
		                
		             } catch (SocketException e) {
		                e.printStackTrace();
		             } catch (IOException e) {
		                e.printStackTrace();
		             }
    				
    				
        			// enable capture
        			captureEnable = true;
        			
                	// infinite loop ?
                    logger.info("While loop");
                	while(captureEnable) {
            	        try {
            				Thread.sleep(500);
            			} catch (InterruptedException e) {
            				// TODO Auto-generated catch block
            				e.printStackTrace();
            			}
                	}
        		}
        	}
        	
        }

        dongle.shutdown();
        logger.info("Sniffer closed.");
    }	
	
}
