package net.electra.net;

import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import net.electra.Settings;
import net.electra.Timer;
import net.electra.io.DataBuffer;
import net.electra.net.events.DisconnectEvent;
import net.electra.net.events.NetworkEvent;
import net.electra.services.Servicable;

public class Client
{
	private final SelectionKey selectionKey;
	private boolean disconnecting = false;
	private final ByteBuffer inboundTemp;
	private final DataBuffer outbound;
	private final DataBuffer inbound;
	private final Timer timeoutTimer;
	private Servicable<?> receiver;
	
	public Client(SelectionKey selectionKey)
	{
		this.selectionKey = selectionKey;
		this.inboundTemp = ByteBuffer.allocateDirect(512);
		this.inbound = new DataBuffer(new byte[512]);
		this.outbound = new DataBuffer(new byte[4096]);
		this.timeoutTimer = new Timer();
		selectionKey.attach(this);
	}
	
	public void associate(Servicable<?> receiver)
	{
		this.receiver = receiver;
	}
	
	public void disconnect(DisconnectReason reason)
	{
		disconnect(reason, true);
	}
	
	public void disconnect(DisconnectReason reason, boolean fireEvent)
	{
		if (!disconnecting)
		{
			disconnecting = true;
			flush(true);
			
			if (receiver != null)
			{
				receiver.fire(new DisconnectEvent(reason));
			}
			else
			{
				System.out.println("Client (" + socketChannel().socket() + ") disconnecting: " + reason);
			}
		}
		
		try
		{
			socketChannel().close();
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}
		
		selectionKey.cancel();
	}
	
	public void flush()
	{
		flush(false);
	}
	
	private void flush(boolean disconnecting)
	{
		try
		{
			outbound.flip(); // back to the beginning
			
			if (outbound.hasRemaining())
			{
				// 4 for testing purposes, TODO: change?
				int writeAmount = (outbound.remaining() >= Settings.CLIENT_BUFFER_SIZE * 4 ? Settings.CLIENT_BUFFER_SIZE * 4 : outbound.remaining());
				int amount = socketChannel().write(ByteBuffer.wrap(outbound.get(writeAmount))); // read writeAmount bytes and write
				
				if (amount > 0)
				{
					timeoutTimer.reset();
				}
				// for some reason jaggrab doesn't do well with large amounts of data, TODO: look into that.
			}
			
			if (outbound.hasRemaining()) // if stuff is still remaining
			{
				outbound.compact(); // if there's still data left then move it to the front of the buffer
			}
			else
			{
				outbound.clear();
			}
		}
		catch (Exception ex)
		{
			if (!disconnecting)
			{
				disconnect(DisconnectReason.DATA_TRANSFER_ERROR);
			}
		}
	}
	
	public void read()
	{
		if (!connected())
		{
			disconnect(DisconnectReason.DISCONNECTED);
			return;
		}
		
		if (timeoutTimer.elapsed() >= Settings.SOCKET_TIMEOUT)
		{
			disconnect(DisconnectReason.DATA_TRANSFER_TIMEOUT);
			return;
		}
		
		int id = -1; // up here so we can use it in error reporting
		int length = -1;
		int readAmount = -1;
		
		try
		{
			if ((readAmount = socketChannel().read(inboundTemp)) == -1) // read into temp (pos = readAmount)
			{
				disconnect(DisconnectReason.DATA_TRANSFER_ERROR);
				return;
			}
			
			if (readAmount > 0)
			{
				timeoutTimer.reset();
			}
			
			boolean hasEnoughData = true;
			
			inboundTemp.flip(); // start reading from the beginning (pos = 0)
			inbound.put(inboundTemp); // put all the data into the inbound buffer (pod = inboundTemp length)
			inbound.flip(); // flip it so we can read from the beginning (pos = 0)
			//inbound.compact(); // doubt i even need this, keep it in for lols
			inboundTemp.clear(); // clear temporary buffer
			
			while (hasEnoughData && inbound.hasRemaining())
			{
				inbound.mark();
				
				id = inbound.getHeader();
				NetworkEvent event = ((NetworkService<?, ?>)receiver.service()).networkEvents().get(id).newInstance();
				length = event.length();
				// TODO: static hiding, create static methods in NetworkEvent for id and length and then hide them with static methods in the "event." this is how you implement
				// static method inheritence. one annoyance is that you have to suppress the warnings to reference "NetworkEvent" statically instead of through instances
				// how it works now is fine, though. this is just a thought for better design.
				
				if (length == -1 && inbound.hasRemaining())
				{
					length = inbound.getUnsigned();
				}
				
				if (length == -1 || inbound.remaining() < length)
				{
					hasEnoughData = false;
					break;
				}
				
				//System.out.println("Received " + event.getClass().getSimpleName() + " (id: " + id + ", len: " + length + ")");
				event.parse(new DataBuffer(inbound.get(length)));
				receiver.fire(event);
			}
			
			if (!hasEnoughData)
			{
				inbound.reset(); // go back to where we started
				inbound.compact(); // move everything to the front
			}
			else
			{
				inbound.clear();
			}
		}
		catch (Exception ex)
		{
			System.err.println("Error parsing " + id + " (len: " + length + ", read: " + readAmount + ")");
			//ex.printStackTrace();
			disconnect(DisconnectReason.DATA_TRANSFER_ERROR);
		}
	}
	
	public <T extends NetworkEvent> void write(T event)
	{
		receiver.fire(event);
		
		if (event.chainBroken())
		{
			System.out.println(event + ": chain broken");
			return;
		}
		
		if (event.id() != -1)
		{
			outbound.putHeader(event.id());
		}
		
		if (event.length() == -1)
		{
			outbound.putLengthByte();
		}
		else if (event.length() == -2)
		{
			outbound.putLengthShort();
		}
		
		event.build(outbound);
		
		if (event.length() == -1)
		{
			outbound.finishByteHeader();
		}
		else if (event.length() == -2)
		{
			outbound.finishShortHeader();
		}
	}
	
	public void write(DataBuffer buffer)
	{
		outbound.put(buffer);
	}
	
	public Servicable<?> receiver()
	{
		return receiver;
	}
	
	public DataBuffer out()
	{
		return outbound;
	}
	
	public DataBuffer in()
	{
		return inbound;
	}
	
	public SocketChannel socketChannel()
	{
		return (SocketChannel)selectionKey().channel();
	}
	
	public SelectionKey selectionKey()
	{
		return selectionKey;
	}
	
	public boolean connected()
	{
		return socketChannel().isConnected();
	}
}
