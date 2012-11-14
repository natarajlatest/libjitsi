/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.device;

import java.lang.ref.*;
import java.util.*;

import javax.media.*;
import javax.media.format.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.jmfext.media.renderer.audio.*;
import org.jitsi.impl.neomedia.portaudio.*;
import org.jitsi.util.*;

/**
 * Creates PortAudio capture devices by enumerating all host devices that have
 * input channels.
 *
 * @author Damian Minkov
 * @author Lyubomir Marinov
 */
public class PortAudioSystem
    extends AudioSystem
{
    /**
     * The <tt>Logger</tt> used by the <tt>PortAudioSystem</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(PortAudioSystem.class);

    /**
     * The protocol of the <tt>MediaLocator</tt>s identifying PortAudio
     * <tt>CaptureDevice</tt>s
     */
    private static final String LOCATOR_PROTOCOL = LOCATOR_PROTOCOL_PORTAUDIO;

    /**
     * The number of times that {@link #willPaOpenStream()} has been
     * invoked without an intervening {@link #didPaOpenStream()} i.e. the
     * number of PortAudio clients which are currently executing
     * <tt>Pa_OpenStream</tt> and which are thus inhibiting
     * <tt>Pa_UpdateAvailableDeviceList</tt>.
     */
    private static int paOpenStream = 0;

    /**
     * The <tt>Object</tt> which synchronizes that access to
     * {@link #paOpenStream} and {@link #paUpdateAvailableDeviceList}.
     */
    private static final Object paOpenStreamSyncRoot = new Object();

    /**
     * The number of times that {@link #willPaUpdateAvailableDeviceList()}
     * has been invoked without an intervening
     * {@link #didPaUpdateAvailableDeviceList()} i.e. the number of
     * PortAudio clients which are currently executing
     * <tt>Pa_UpdateAvailableDeviceList</tt> and which are thus inhibiting
     * <tt>Pa_OpenStream</tt>.
     */
    private static int paUpdateAvailableDeviceList = 0;

    /**
     * The <tt>Object</tt> which ensures that PortAudio's native function
     * <tt>Pa_UpdateAvailableDeviceList()</tt> will not be invoked concurrently.
     * The condition should hold true on the native side but, anyway, it shoul
     * not hurt (much) to enforce it on the Java side as well.
     */
    private static final Object paUpdateAvailableDeviceListSyncRoot
        = new Object();

    /**
     * The list of <tt>PaUpdateAvailableDeviceListListener</tt>s which are to be
     * notified before and after PortAudio's native function
     * <tt>Pa_UpdateAvailableDeviceList()</tt> is invoked.
     */
    private static final List<WeakReference<PaUpdateAvailableDeviceListListener>>
        paUpdateAvailableDeviceListListeners
            = new LinkedList<WeakReference<PaUpdateAvailableDeviceListListener>>();

    private Runnable devicesChangedCallback;

    /**
     * Initializes a new <tt>PortAudioSystem</tt> instance which creates PortAudio
     * capture devices by enumerating all host devices with input channels.
     *
     * @throws Exception if anything wrong happens while creating the PortAudio
     * capture devices
     */
    PortAudioSystem()
        throws Exception
    {
        super(
                LOCATOR_PROTOCOL,
                FEATURE_DENOISE
                    | FEATURE_ECHO_CANCELLATION
                    | FEATURE_NOTIFY_AND_PLAYBACK_DEVICES
                    | FEATURE_REINITIALIZE);
    }

    /**
     * Adds a listener which is to be notified before and after PortAudio's
     * native function <tt>Pa_UpdateAvailableDeviceList()</tt> is invoked.
     * <p>
     * <b>Note</b>: The <tt>PortAudioSystem</tt> class keeps a
     * <tt>WeakReference</tt> to the specified <tt>listener</tt> in order to
     * avoid memory leaks.
     * </p>
     *
     * @param listener the <tt>PaUpdateAvailableDeviceListListener</tt> to be
     * notified before and after PortAudio's native function
     * <tt>Pa_UpdateAvailableDeviceList()</tt> is invoked
     */
    public static void addPaUpdateAvailableDeviceListListener(
            PaUpdateAvailableDeviceListListener listener)
    {
        if (listener == null)
            throw new NullPointerException("listener");

        synchronized (paUpdateAvailableDeviceListListeners)
        {
            Iterator<WeakReference<PaUpdateAvailableDeviceListListener>> i
                = paUpdateAvailableDeviceListListeners.iterator();
            boolean add = true;

            while (i.hasNext())
            {
                PaUpdateAvailableDeviceListListener l = i.next().get();

                if (l == null)
                    i.remove();
                else if (l.equals(listener))
                    add = false;
            }
            if (add)
            {
                paUpdateAvailableDeviceListListeners.add(
                        new WeakReference<PaUpdateAvailableDeviceListListener>(
                                listener));
            }
        }
    }

    @Override
    public Renderer createRenderer(boolean playback)
    {
        MediaLocator locator;

        if (playback)
            locator = null;
        else
        {
            CaptureDeviceInfo notifyDevice
                = getDevice(AudioSystem.NOTIFY_INDEX);

            if (notifyDevice == null)
                return null;
            else
                locator = notifyDevice.getLocator();
        }

        PortAudioRenderer renderer = new PortAudioRenderer(playback);

        if ((renderer != null) && (locator != null))
            renderer.setLocator(locator);

        return renderer;
    }

    /**
     * Notifies <tt>PortAudioSystem</tt> that a PortAudio client finished
     * executing <tt>Pa_OpenStream</tt>.
     */
    public static void didPaOpenStream()
    {
        synchronized (paOpenStreamSyncRoot)
        {
            paOpenStream--;
            if (paOpenStream < 0)
                paOpenStream = 0;

            paOpenStreamSyncRoot.notifyAll();
        }
    }

    /**
     * Notifies <tt>PortAudioSystem</tt> that a PortAudio client finished
     * executing <tt>Pa_UpdateAvailableDeviceList</tt>.
     */
    private static void didPaUpdateAvailableDeviceList()
    {
        synchronized (paOpenStreamSyncRoot)
        {
            paUpdateAvailableDeviceList--;
            if (paUpdateAvailableDeviceList < 0)
                paUpdateAvailableDeviceList = 0;

            paOpenStreamSyncRoot.notifyAll();
        }

        firePaUpdateAvailableDeviceListEvent(false);
    }

    protected void doInitialize()
        throws Exception
    {
        /*
         * If PortAudio fails to initialize because of, for example, a missing
         * native counterpart, it will throw an exception here and the PortAudio
         * Renderer will not be initialized.
         */
        int deviceCount = Pa.GetDeviceCount();
        int channels = 1;
        int sampleSizeInBits = 16;
        long sampleFormat = Pa.getPaSampleFormat(sampleSizeInBits);
        int defaultInputDeviceIndex = Pa.GetDefaultInputDevice();
        int defaultOutputDeviceIndex = Pa.GetDefaultOutputDevice();
        List<ExtendedCaptureDeviceInfo> captureDevices
            = new LinkedList<ExtendedCaptureDeviceInfo>();
        List<ExtendedCaptureDeviceInfo> playbackDevices
            = new LinkedList<ExtendedCaptureDeviceInfo>();

        for (int deviceIndex = 0; deviceIndex < deviceCount; deviceIndex++)
        {
            long deviceInfo = Pa.GetDeviceInfo(deviceIndex);
            String name = Pa.DeviceInfo_getName(deviceInfo);

            if (name != null)
                name = name.trim();

            int maxInputChannels
                = Pa.DeviceInfo_getMaxInputChannels(deviceInfo);
            int maxOutputChannels
                = Pa.DeviceInfo_getMaxOutputChannels(deviceInfo);
            String transportType
                = Pa.DeviceInfo_getTransportType(deviceInfo);
            String deviceUID
                = Pa.DeviceInfo_getDeviceUID(deviceInfo);

            ExtendedCaptureDeviceInfo cdi = null;
            List<ExtendedCaptureDeviceInfo> devices = getDevices(CAPTURE_INDEX);
            boolean found = false;
            // Search if the device is already initiated. If yes used it,
            // otherwise a call currently using this device will fail.
            for(int i = 0; devices !=null && i < devices.size() && !found; ++i)
            {
                // using identifier (name or uid, cause there can be some
                // uid that are null)
                String id = devices.get(i).getIdentifier();
                if(id.equals(deviceUID) || id.equals(name))
                {
                    cdi = devices.get(i);
                    found = true;
                }
            }
            // If not found, then we must initialized this new device.
            if(!found)
            {
                cdi = new ExtendedCaptureDeviceInfo(
                    name,
                    new MediaLocator(LOCATOR_PROTOCOL + ":#" + deviceIndex),
                    new Format[]
                    {
                        new AudioFormat(
                                AudioFormat.LINEAR,
                                (maxInputChannels > 0)
                                    ? getSupportedSampleRate(
                                            true,
                                            deviceIndex,
                                            channels,
                                            sampleFormat)
                                    : Pa.DEFAULT_SAMPLE_RATE,
                                sampleSizeInBits,
                                channels,
                                AudioFormat.LITTLE_ENDIAN,
                                AudioFormat.SIGNED,
                                Format.NOT_SPECIFIED /* frameSizeInBits */,
                                Format.NOT_SPECIFIED /* frameRate */,
                                Format.byteArray)
                    },
                    deviceUID,
                    transportType);
            }

            if (maxInputChannels > 0)
            {
                if (deviceIndex == defaultInputDeviceIndex)
                {
                    captureDevices.add(0, cdi);
                    if (logger.isDebugEnabled())
                        logger.debug("Added default capture device: " + name);
                }
                else
                {
                    captureDevices.add(cdi);
                    if (logger.isDebugEnabled())
                        logger.debug("Added capture device: " + name);
                }
            }
            if (maxOutputChannels > 0)
            {
                if (deviceIndex == defaultOutputDeviceIndex)
                {
                    playbackDevices.add(0, cdi);
                    if (logger.isDebugEnabled())
                        logger.debug("Added default playback device: " + name);
                }
                else
                {
                    playbackDevices.add(cdi);
                    if (logger.isDebugEnabled())
                        logger.debug("Added playback device: " + name);
                }
            }
        }

        setCaptureDevices(captureDevices);
        setPlaybackDevices(playbackDevices);

        if (devicesChangedCallback == null)
        {
            devicesChangedCallback
                = new Runnable()
                {
                    public void run()
                    {
                        try
                        {
                            reinitialize();
                        }
                        catch (Throwable t)
                        {
                            if (t instanceof ThreadDeath)
                                throw (ThreadDeath) t;

                            logger.warn(
                                    "Failed to reinitialize PortAudio devices",
                                    t);
                        }
                    }
                };
            Pa.setDevicesChangedCallback(devicesChangedCallback);
        }
    }

    /**
     * Notifies the registered <tt>PaUpdateAvailableDeviceListListener</tt>s
     * that PortAudio's native function <tt>Pa_UpdateAvailableDeviceList()</tt>
     * will be or was invoked.
     *
     * @param will <tt>true</tt> if PortAudio's native function
     * <tt>Pa_UpdateAvailableDeviceList()</tt> will be invoked or <tt>false</tt>
     * if it was invoked
     */
    private static void firePaUpdateAvailableDeviceListEvent(boolean will)
    {
        try
        {
            List<WeakReference<PaUpdateAvailableDeviceListListener>> ls;

            synchronized (paUpdateAvailableDeviceListListeners)
            {
                ls
                    = new ArrayList<WeakReference<PaUpdateAvailableDeviceListListener>>(
                            paUpdateAvailableDeviceListListeners);
            }

            for (WeakReference<PaUpdateAvailableDeviceListListener> wr : ls)
            {
                PaUpdateAvailableDeviceListListener l = wr.get();

                if (l != null)
                {
                    try
                    {
                        if (will)
                            l.willPaUpdateAvailableDeviceList();
                        else
                            l.didPaUpdateAvailableDeviceList();
                    }
                    catch (Throwable t)
                    {
                        if (t instanceof ThreadDeath)
                            throw (ThreadDeath) t;
                        else
                        {
                            logger.error(
                                    "PaUpdateAvailableDeviceListListener."
                                        + (will ? "will" : "did")
                                        + "PaUpdateAvailableDeviceList failed.",
                                    t);
                        }
                    }
                }
            }
        }
        catch (Throwable t)
        {
            if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
        }
    }

    /**
     * Gets a sample rate supported by a PortAudio device with a specific device
     * index with which it is to be registered with JMF.
     *
     * @param input <tt>true</tt> if the supported sample rate is to be retrieved for
     * the PortAudio device with the specified device index as an input device
     * or <tt>false</tt> for an output device
     * @param deviceIndex the device index of the PortAudio device for which a
     * supported sample rate is to be retrieved
     * @param channelCount number of channel
     * @param sampleFormat sample format
     * @return a sample rate supported by the PortAudio device with the
     * specified device index with which it is to be registered with JMF
     */
    private static double getSupportedSampleRate(
            boolean input,
            int deviceIndex,
            int channelCount,
            long sampleFormat)
    {
        long deviceInfo = Pa.GetDeviceInfo(deviceIndex);
        double supportedSampleRate;

        if (deviceInfo != 0)
        {
            double defaultSampleRate
                = Pa.DeviceInfo_getDefaultSampleRate(deviceInfo);

            if (defaultSampleRate >= MediaUtils.MAX_AUDIO_SAMPLE_RATE)
                supportedSampleRate = defaultSampleRate;
            else
            {
                long streamParameters
                    = Pa.StreamParameters_new(
                            deviceIndex,
                            channelCount,
                            sampleFormat,
                            Pa.LATENCY_UNSPECIFIED);

                if (streamParameters == 0)
                    supportedSampleRate = defaultSampleRate;
                else
                {
                    try
                    {
                        long inputParameters;
                        long outputParameters;

                        if (input)
                        {
                            inputParameters = streamParameters;
                            outputParameters = 0;
                        }
                        else
                        {
                            inputParameters = 0;
                            outputParameters = streamParameters;
                        }

                        boolean formatIsSupported
                            = Pa.IsFormatSupported(
                                    inputParameters,
                                    outputParameters,
                                    Pa.DEFAULT_SAMPLE_RATE);

                        supportedSampleRate
                            = formatIsSupported
                                ? Pa.DEFAULT_SAMPLE_RATE
                                : defaultSampleRate;
                    }
                    finally
                    {
                        Pa.StreamParameters_free(streamParameters);
                    }
                }
            }
        }
        else
            supportedSampleRate = Pa.DEFAULT_SAMPLE_RATE;
        return supportedSampleRate;
    }

    /**
     * Reinitializes this <tt>PortAudioSystem</tt> in order to bring it up to
     * date with possible changes in the PortAudio devices. Invokes
     * <tt>Pa_UpdateAvailableDeviceList()</tt> to update the devices on the
     * native side and then {@link #initialize()} to reflect any changes on the
     * Java side. Invoked by PortAudio when it detects that the list of devices
     * has changed.
     *
     * @throws Exception if there was an error during the invocation of
     * <tt>Pa_UpdateAvailableDeviceList()</tt> and
     * <tt>DeviceSystem.initialize()</tt>
     */
    private void reinitialize()
        throws Exception
    {
        synchronized (paUpdateAvailableDeviceListSyncRoot)
        {
            willPaUpdateAvailableDeviceList();
            try
            {
                Pa.UpdateAvailableDeviceList();
            }
            finally
            {
                didPaUpdateAvailableDeviceList();
            }
        }

        /*
         * XXX We will likely minimize the risk of crashes on the native side
         * even further by invoking initialize() with
         * Pa_UpdateAvailableDeviceList locked. Unfortunately, that will likely
         * increase the risks of deadlocks on the Java side.
         */
        initialize();
    }

    public static void removePaUpdateAvailableDeviceListListener(
            PaUpdateAvailableDeviceListListener listener)
    {
        if (listener == null)
            return;

        synchronized (paUpdateAvailableDeviceListListeners)
        {
            Iterator<WeakReference<PaUpdateAvailableDeviceListListener>> i
                = paUpdateAvailableDeviceListListeners.iterator();

            while (i.hasNext())
            {
                PaUpdateAvailableDeviceListListener l = i.next().get();

                if ((l == null) || l.equals(listener))
                    i.remove();
            }
        }
    }

    @Override
    public String toString()
    {
        return "PortAudio";
    }

    /**
     * Waits for all PortAudio clients to finish executing
     * <tt>Pa_OpenStream</tt>.
     */
    private static void waitForPaOpenStream()
    {
        boolean interrupted = true;

        while (paOpenStream > 0)
        {
            try
            {
                paOpenStreamSyncRoot.wait();
            }
            catch (InterruptedException ie)
            {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Waits for all PortAudio clients to finish executing
     * <tt>Pa_UpdateAvailableDeviceList</tt>.
     */
    private static void waitForPaUpdateAvailableDeviceList()
    {
        boolean interrupted = true;

        while (paUpdateAvailableDeviceList > 0)
        {
            try
            {
                paOpenStreamSyncRoot.wait();
            }
            catch (InterruptedException ie)
            {
                interrupted = true;
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    /**
     * Notifies <tt>PortAudioSystem</tt> that a PortAudio client will start
     * executing <tt>Pa_OpenStream</tt>.
     */
    public static void willPaOpenStream()
    {
        synchronized (paOpenStreamSyncRoot)
        {
            waitForPaUpdateAvailableDeviceList();

            paOpenStream++;
            paOpenStreamSyncRoot.notifyAll();
        }
    }

    /**
     * Notifies <tt>PortAudioSystem</tt> that a PortAudio client will start
     * executing <tt>Pa_UpdateAvailableDeviceList</tt>.
     */
    private static void willPaUpdateAvailableDeviceList()
    {
        synchronized (paOpenStreamSyncRoot)
        {
            waitForPaOpenStream();

            paUpdateAvailableDeviceList++;
            paOpenStreamSyncRoot.notifyAll();
        }

        firePaUpdateAvailableDeviceListEvent(true);
    }

    /**
     * Represents a listener which is to be notified before and after
     * PortAudio's native function <tt>Pa_UpdateAvailableDeviceList()</tt> is
     * invoked.
     */
    public interface PaUpdateAvailableDeviceListListener
        extends EventListener
    {
        /**
         * Notifies this listener that PortAudio's native function
         * <tt>Pa_UpdateAvailableDeviceList()</tt> was invoked.
         *
         * @throws Exception if this implementation encounters an error. Any
         * <tt>Throwable</tt> apart from <tt>ThreadDeath</tt> will be ignored
         * after it is logged for debugging purposes.
         */
        void didPaUpdateAvailableDeviceList()
            throws Exception;

        /**
         * Notifies this listener that PortAudio's native function
         * <tt>Pa_UpdateAvailableDeviceList()</tt> will be invoked.
         *
         * @throws Exception if this implementation encounters an error. Any
         * <tt>Throwable</tt> apart from <tt>ThreadDeath</tt> will be ignored
         * after it is logged for debugging purposes.
         */
        void willPaUpdateAvailableDeviceList()
            throws Exception;
    }
}
