package org.gcash.garagedoor;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class CustomLogFormatter extends Formatter {
    @Override
    public String format(LogRecord record) {

        // just the date and the message
        Date date = new Date(record.getMillis());
        Format formatter = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss.SSS");
        String s = null;
        s = formatter.format(date);
        return s + " " + formatMessage(record) + "\n";
    }
}
