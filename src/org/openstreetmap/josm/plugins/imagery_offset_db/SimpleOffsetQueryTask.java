// License: WTFPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.imagery_offset_db;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * A task to query the imagery offset server and process the response.
 *
 * @author Zverik
 * @license WTFPL
 */
class SimpleOffsetQueryTask extends PleaseWaitRunnable {
    private String query;
    private String errorMessage;
    private String title;
    protected boolean cancelled;
    private QuerySuccessListener listener;

    /**
     * Initialize the task.
     * @param query A query string, usually starting with an action word and a question mark.
     * @param title A title for the progress monitor.
     */
    SimpleOffsetQueryTask(String query, String title) {
        super(ImageryOffsetTools.DIALOG_TITLE);
        this.query = query;
        this.title = title;
        cancelled = false;
    }

    /**
     * In case a query was not specified when the object was constructed,
     * it can be set with this method.
     * @param query A query string, usually starting with an action word and a question mark.
     * @see #SimpleOffsetQueryTask(String, String)
     */
    public void setQuery(String query) {
        this.query = query;
    }

    /**
     * Install a listener for successful responses. There can be only one.
     * @param listener success listener
     */
    public void setListener(QuerySuccessListener listener) {
        this.listener = listener;
    }

    /**
     * Remove a listener for successful responses.
     */
    public void removeListener() {
        this.listener = null;
    }

    /**
     * The main method: calls {@link #doQuery(java.lang.String)} and processes exceptions.
     */
    @Override
    protected void realRun() {
        getProgressMonitor().indeterminateSubTask(title);
        try {
            errorMessage = null;
            doQuery(query);
        } catch (UploadException e) {
            errorMessage = tr("Server has rejected the request") + ":\n" + e.getMessage();
        } catch (IOException e) {
            errorMessage = tr("Unable to connect to the server") + "\n" + e.getMessage();
        }
    }

    /**
     * Sends a request to the imagery offset server. Processes exceptions and
     * return codes, calls {@link #processResponse(java.io.InputStream)} on success.
     * @param query A query string, usually starting with an action word and a question mark.
     * @throws UploadException in case of upload error
     * @throws IOException in case of other I/O error
     */
    private void doQuery(String query) throws UploadException, IOException {
        try {
            String serverURL = Config.getPref().get("iodb.server.url", "https://offsets.textual.ru/");
            URL url = new URL(serverURL + query);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.connect();
            if (connection.getResponseCode() != 200) {
                throw new IOException("HTTP Response code " + connection.getResponseCode() + " (" + connection.getResponseMessage() + ")");
            }
            InputStream inp = connection.getInputStream();
            if (inp == null)
                throw new IOException("Empty response");
            try {
                if (!cancelled)
                    processResponse(inp);
            } finally {
                connection.disconnect();
            }
        } catch (MalformedURLException ex) {
            throw new IOException("Malformed URL: " + ex.getMessage());
        }
    }

    /**
     * Doesn't actually cancel, just raises a flag.
     */
    @Override
    protected void cancel() {
        cancelled = true;
    }

    /**
     * Is called after {@link #realRun()}. Either displays an error message
     * or notifies a listener of success.
     */
    @Override
    protected void finish() {
        if (errorMessage != null) {
            JOptionPane.showMessageDialog(MainApplication.getMainFrame(), errorMessage,
                    ImageryOffsetTools.DIALOG_TITLE, JOptionPane.ERROR_MESSAGE);
        } else if (listener != null) {
            listener.queryPassed();
        }
    }

    /**
     * Parse the response input stream and determine whether an operation
     * was successful or not.
     * @param inp input stream
     * @throws UploadException Thrown if an error message was found.
     */
    protected void processResponse(InputStream inp) throws UploadException {
        String response = "";
        if (inp != null) {
            Scanner sc = new Scanner(inp, StandardCharsets.UTF_8.name()).useDelimiter("\\A");
            response = sc.hasNext() ? sc.next() : "";
        }
        Pattern p = Pattern.compile("<(\\w+)>([^<]+)</\\1>");
        Matcher m = p.matcher(response);
        if (m.find()) {
            if (m.group(1).equals("error")) {
                throw new UploadException(m.group(2));
            }
        } else {
            throw new UploadException("No response");
        }
    }

    /**
     * A placeholder exception for error messages.
     */
    public static class UploadException extends Exception {
        UploadException(String message) {
            super(message);
        }
    }
}
