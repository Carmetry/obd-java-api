package com.github.pires.obd.commands;

import com.github.pires.obd.exceptions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Base OBD command.
 */
public abstract class ObdCommand {

    /**
     * Error classes to be tested in order
     */
    private final Class[] ERROR_CLASSES = {
            UnableToConnectException.class,
            BusInitException.class,
            MisunderstoodCommandException.class,
            NoDataException.class,
            StoppedException.class,
            UnknownErrorException.class,
            UnsupportedCommandException.class
    };
    protected ArrayList<Integer> buffer = null;
    protected String cmd = null;
    protected boolean useImperialUnits = false;
    protected String rawData = null;
    private long responseTimeDelay;
    private long start;
    private long end;
    private ObdCommandMode mode;

    public enum ObdCommandMode {

        CURRENT_DATA("01", true),
        FREEZE_FRAME_DATA("02", true),
        STORED_DTCS("03", false),
        CLEAR_DTCS("04", false),
        VEHICLE_INFOTATION("09", true),
        PERMANENT_DTCS("0A", false);

        private final String modeStr;
        private final boolean usesPID;

        ObdCommandMode(String modeStr, boolean usesPID) {
            this.modeStr = modeStr;
            this.usesPID = usesPID;
        }

        public String getModeStr() {
            return modeStr;
        }

        public boolean usesPID() {
            return usesPID;
        }
    }

    /**
     * Default ctor to use
     *
     * @param command the command to send
     */
    public ObdCommand(String command) {

        this.cmd = command;
        this.buffer = new ArrayList<>();

        //Set mode according to contents of command
        String modeStr = command.substring(0, 2);

        for (ObdCommandMode mode : ObdCommandMode.values()) {
            if (mode.getModeStr().equals(modeStr)) {
                this.mode = mode;
                break;
            }
        }
    }

    /**
     * Default ctor for setting a write-read delat
     *
     * @param command           command to send
     * @param responseTimeDelay milliseconds to wait between writing the command and reading the response
     */
    public ObdCommand(String command, long responseTimeDelay) {
        this(command);
        this.responseTimeDelay = responseTimeDelay;
    }

    /**
     * Creates a new ObdCommand with the specified mode, and PID if required by {@link ObdCommandMode#usesPID()}
     *
     * @param mode the mode to use by the command
     * @param PID  the PID to send
     * @throws IllegalArgumentException When PID is null and mode requires a PID
     */
    public ObdCommand(ObdCommandMode mode, String PID) {

        StringBuilder builder = new StringBuilder(5);
        builder.append(mode.getModeStr());

        if (mode.usesPID()) {
            if (PID == null)
                throw new IllegalArgumentException("Specified null PID with mode " + mode.getModeStr() + ", which requires one");

            builder.append(' ').append(PID);
        }
    }

    /**
     * Creates a new ObdCommand with the specified mode, and PID if required by {@link ObdCommandMode#usesPID()} and sets a write-read delat
     *
     * @param mode              mode to use by the command
     * @param PID               PID to send
     * @param responseTimeDelay milliseconds to wait between writing the command and reading the response
     * @throws IllegalArgumentException When PID is null and mode requires a PID
     */
    public ObdCommand(ObdCommandMode mode, String PID, long responseTimeDelay) {
        this(mode, PID);
        this.responseTimeDelay = responseTimeDelay;
    }

    /**
     * Copy ctor.
     *
     * @param other the ObdCommand to copy.
     */
    public ObdCommand(ObdCommand other) {
        this(other.cmd);
    }

    /**
     * Sends the OBD-II request and deals with the response.
     * <p/>
     * This method CAN be overriden in fake commands.
     *
     * @param in  a {@link java.io.InputStream} object.
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    public void run(InputStream in, OutputStream out) throws IOException,
            InterruptedException {
        start = System.currentTimeMillis();
        sendCommand(out);

        //Some systems may take a while to answer, so we can make this Thread sleep for a while
        if (responseTimeDelay != 0)
            Thread.sleep(responseTimeDelay);

        readResult(in);
        end = System.currentTimeMillis();
    }

    /**
     * Sends the OBD-II request.
     * <p/>
     * This method may be overriden in subclasses, such as ObMultiCommand or
     * TroubleCodesCommand.
     *
     * @param out The output stream.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void sendCommand(OutputStream out) throws IOException,
            InterruptedException {
        // write to OutputStream (i.e.: a BluetoothSocket) with an added
        // Carriage return
        out.write((cmd + "\r").getBytes());
        out.flush();
    }

    /**
     * Resends this command.
     *
     * @param out a {@link java.io.OutputStream} object.
     * @throws java.io.IOException            if any.
     * @throws java.lang.InterruptedException if any.
     */
    protected void resendCommand(OutputStream out) throws IOException,
            InterruptedException {
        out.write("\r".getBytes());
        out.flush();
    }

    /**
     * Reads the OBD-II response.
     * <p/>
     * This method may be overriden in subclasses, such as ObdMultiCommand.
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readResult(InputStream in) throws IOException {
        readRawData(in);
        checkForErrors();
        fillBuffer();
        performCalculations();
    }

    /**
     * This method exists so that for each command, there must be a method that is
     * called only once to perform calculations.
     */
    protected abstract void performCalculations();

    /**
     *
     */
    protected void fillBuffer() {
        rawData = rawData.replaceAll("\\s", ""); //removes all [ \t\n\x0B\f\r]
        rawData = rawData.replaceAll("(BUS INIT)|(BUSINIT)|(\\.)", "");

        if (!rawData.matches("([0-9A-F])+")) {
            throw new NonNumericResponseException(rawData);
        }

        // read string each two chars
        buffer.clear();
        int begin = 0;
        int end = 2;
        while (end <= rawData.length()) {
            buffer.add(Integer.decode("0x" + rawData.substring(begin, end)));
            begin = end;
            end += 2;
        }
    }

    /**
     * <p>
     * readRawData.</p>
     *
     * @param in a {@link java.io.InputStream} object.
     * @throws java.io.IOException if any.
     */
    protected void readRawData(InputStream in) throws IOException {
        byte b = 0;
        StringBuilder res = new StringBuilder();

        // read until '>' arrives OR end of stream reached
        char c;
        while(true)
        {
      	  b = (byte) in.read();
      	  if(b == -1) // -1 if the end of the stream is reached
      	  {
      		  break;
      	  }
      	  c = (char)b;
      	  if(c == '>') // read until '>' arrives
      	  {
      		  break;
      	  }
      	  res.append(c);
        }

    /*
     * Imagine the following response 41 0c 00 0d.
     *
     * ELM sends strings!! So, ELM puts spaces between each "byte". And pay
     * attention to the fact that I've put the word byte in quotes, because 41
     * is actually TWO bytes (two chars) in the socket. So, we must do some more
     * processing..
     */
        rawData = res.toString().replaceAll("SEARCHING", "");

    /*
     * Data may have echo or informative text like "INIT BUS..." or similar.
     * The response ends with two carriage return characters. So we need to take
     * everything from the last carriage return before those two (trimmed above).
     */
        //kills multiline.. rawData = rawData.substring(rawData.lastIndexOf(13) + 1);
        rawData = rawData.replaceAll("\\s", "");//removes all [ \t\n\x0B\f\r]
    }

    void checkForErrors() {
        for (Class<? extends ResponseException> errorClass : ERROR_CLASSES) {
            ResponseException messageError;

            try {
                messageError = errorClass.newInstance();
                messageError.setCommand(this.cmd);
            } catch (InstantiationException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            if (messageError.isError(rawData)) {
                throw messageError;
            }
        }
    }

    /**
     * @return the raw command response in string representation.
     */
    public String getResult() {
        return rawData;
    }

    /**
     * @return a formatted command response in string representation.
     */
    public abstract String getFormattedResult();

    /**
     * @return the command response in string representation, without formatting.
     */
    public abstract String getCalculatedResult();

    /**
     * @return a list of integers
     */
    protected ArrayList<Integer> getBuffer() {
        return buffer;
    }

    /**
     * @return true if imperial units are used, or false otherwise
     */
    public boolean useImperialUnits() {
        return useImperialUnits;
    }

    /**
     * The unit of the result, as used in {@link #getFormattedResult()}
     *
     * @return a String representing a unit or "", never null
     */
    public String getResultUnit() {
        return "";//no unit by default
    }

    /**
     * Set to 'true' if you want to use imperial units, false otherwise. By
     * default this value is set to 'false'.
     *
     * @param isImperial a boolean.
     */
    public void useImperialUnits(boolean isImperial) {
        this.useImperialUnits = isImperial;
    }

    /**
     * @return the OBD command name.
     */
    public abstract String getName();

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @return delay in ms
     */
    public long getResponseTimeDelay() {
        return responseTimeDelay;
    }

    /**
     * Time the command waits before returning from #sendCommand()
     *
     * @param responseTimeDelay
     */
    public void setResponseTimeDelay(long responseTimeDelay) {
        this.responseTimeDelay = responseTimeDelay;
    }

    //fixme resultunit
    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
    }

    public final String getCommandPID() {
        return cmd.substring(3);
    }

    public final String getCommandModeCode() {
        return cmd.substring(0, 2);
    }

    public final ObdCommandMode getCommandMode() {
        return mode;
    }

}
