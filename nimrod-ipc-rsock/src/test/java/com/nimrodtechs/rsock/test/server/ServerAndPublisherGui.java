package com.nimrodtechs.rsock.test.server;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.nimrodtechs.ipcrsock.common.SubscriptionListener;
import com.nimrodtechs.ipcrsock.common.SubscriptionRequest;
import com.nimrodtechs.ipcrsock.publisher.PublisherSocketImpl;
import com.nimrodtechs.ipcrsock.server.ManualRsocketServer;
import com.nimrodtechs.rsock.test.model.MarketData;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.*;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Component
public class ServerAndPublisherGui extends JDialog implements SubscriptionListener {

    @Value("${nimrod.rsock.serverName:#{null}}")
    String serverName;

    @Value("${spring.rsocket.server.port:#{null}}")
    String serverPort;

    @Autowired
    PublisherSocketImpl publisherSocket;

    @Autowired(required = false)
    ManualRsocketServer manualRsocketServer;

    private DefaultListModel subscriptionList = new DefaultListModel();
    private static final int BOUND = 100;
    private Random random = new Random();
    private AtomicInteger atomicInteger = new AtomicInteger();

    private volatile boolean keepLooping = true;

    public ServerAndPublisherGui() {
        initComponents();
        btnStart.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    manualRsocketServer.startListeningWithPort(Integer.valueOf(txtPort.getText()));
                } catch (Exception ex) {
                    //show something u=in GUI
                    ex.printStackTrace();
                }
            }
        });
    }

    @PostConstruct
    void init() {
        System.out.println("HERE");
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);

        buttonOK.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOK();
            }
        });

        buttonCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        listSubscriptions.addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (listSubscriptions.getSelectedValue() == null) {
                    return;
                }
                System.out.println("Selected " + listSubscriptions.getSelectedValue());
                txtSubject.setText(((SubscriptionRequest) listSubscriptions.getSelectedValue()).getSubject());
            }
        });
        publishButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(chkLoop.isSelected()) {
                    startLoopingThread();
                } else {
                    //publisherSocket.publish(txtSubject.getText(), new MarketData(txtData.getText(), random.nextInt(BOUND)));
                    publisherSocket.publish(txtSubject.getText(), new MarketData(txtData.getText(), atomicInteger.incrementAndGet()));
                }
            }
        });

        btnStop.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if(chkLoop.isSelected()) {
                    stopLooping();
                }
            }
        });

        publisherSocket.addSubscriptionListener(this);
        listSubscriptions.setModel(subscriptionList);
        listSubscriptions.setCellRenderer(new CustomListBoxRenderer());

        tabbedPane1.setTitleAt(1, "RMI Server:" + serverName + ":" + (serverPort == null ? "manual" : serverPort));

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "PublisherRateLoop");
            t.setDaemon(true);
            return t;
        });
    }

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduledTask;
    private AtomicInteger sentCount = new AtomicInteger();

    private void startLoopingThread() {
        if (txtSubject.getText() == null || txtSubject.getText().isEmpty())
            return;

        int msgsPerSecond = 1;
        int totalMsgs = Integer.MAX_VALUE;

        if (txtMsgPerSec.getText() != null && !txtMsgPerSec.getText().isEmpty()) {
            msgsPerSecond = Integer.parseInt(txtMsgPerSec.getText());
        }
        if (txtTotalMsgs.getText() != null && !txtTotalMsgs.getText().isEmpty()) {
            totalMsgs = Integer.parseInt(txtTotalMsgs.getText());
        }

        final int finalMsgsPerSecond = msgsPerSecond;
        final int finalTotalMsgs = totalMsgs;

        final String subjectInput = txtSubject.getText().trim();
        final String data = txtData.getText();

        keepLooping = true;
        sentCount.set(0);



        // Interval between messages in nanoseconds
        long intervalNanos = 1_000_000_000L / finalMsgsPerSecond;

        // If "ALL", prepare to cycle through JList subjects
        final ListModel<SubscriptionRequest> model = listSubscriptions.getModel();
        Stream<SubscriptionRequest> stream = IntStream.range(0, model.getSize())
                .mapToObj(model::getElementAt);
        java.util.List<String> allItems = stream
                .map(SubscriptionRequest::getSubject)
                .toList();
        final int subjectCount = model.getSize();
        final boolean allMode = subjectInput.equalsIgnoreCase("ALL");
        final AtomicInteger subjectIndex = new AtomicInteger(0);

        Runnable task = () -> {
            if (!keepLooping) {
                if (scheduledTask != null) scheduledTask.cancel(false);
                return;
            }
            int sent = sentCount.incrementAndGet();

            //subject is either a fixed subject selected fom list OR its ALL which means step thru all the subjects in the List and when at bottom start again
            String subject;
            if (allMode && subjectCount > 0) {
                int idx = subjectIndex.getAndUpdate(i -> (i + 1) % subjectCount);
                subject = allItems.get(idx);
            } else {
                subject = subjectInput;
            }

            publisherSocket.publish(subject, "count="+sent+"["+data+"]");

            if (sent >= finalTotalMsgs) {
                keepLooping = false;
                if (scheduledTask != null) {
                    scheduledTask.cancel(false);
                }
                System.out.println("Completed " + sent + " messages");
            }
        };

        scheduledTask = executor.scheduleAtFixedRate(
                task,
                0L,
                intervalNanos,
                TimeUnit.NANOSECONDS
        );
    }

    public void stopLooping() {
        keepLooping = false;
        if (executor != null) {
            if (scheduledTask != null)
                scheduledTask.cancel(false);
        }
    }

    private void onOK() {
        if (JOptionPane.showConfirmDialog(this, "Are you sure?") == 0) {
            // add your code here
            dispose();
            System.exit(0);
        }

    }

    private void onCancel() {
        if (JOptionPane.showConfirmDialog(this, "Are you sure?") == 0) {
            // add your code here
            dispose();
            System.exit(0);
        }
    }

    public static void main(String[] args) {
        ServerAndPublisherGui dialog = new ServerAndPublisherGui();
        dialog.pack();
        dialog.setVisible(true);
        System.exit(0);
    }

    class CustomListBoxRenderer extends DefaultListCellRenderer {
        @Override
        public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                               boolean isSelected, boolean cellHasFocus) {
            if (value instanceof SubscriptionRequest) {
                value = ((SubscriptionRequest) value).getRequestor() + ":" + ((SubscriptionRequest) value).getSubject();
            }
            return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        }

    }


    @Override
    public void onSubscription(SubscriptionRequest subscriptionRequest) {
        SwingUtilities.invokeLater(() -> {
            subscriptionList.addElement(subscriptionRequest);
        });
    }

    @Override
    public void onSubscriptionRemove(SubscriptionRequest subscriptionRequest) {
        SwingUtilities.invokeLater(() -> {
            subscriptionList.removeElement(subscriptionRequest);
            //TODO need more checking that this corresponds to a looping publish..OK for now
            if(chkLoop.isSelected()) {
                stopLooping();
            }
        });
    }


    private void initComponents() {
        // JFormDesigner - Component initialization - DO NOT MODIFY  //GEN-BEGIN:initComponents  @formatter:off
        contentPane = new JPanel();
        var panel1 = new JPanel();
        var hSpacer1 = new Spacer();
        var panel2 = new JPanel();
        buttonOK = new JButton();
        buttonCancel = new JButton();
        var panel3 = new JPanel();
        tabbedPane1 = new JTabbedPane();
        var panel4 = new JPanel();
        var panel5 = new JPanel();
        scrollPane2 = new JScrollPane();
        listSubscriptions = new JList();
        var panel6 = new JPanel();
        var label1 = new JLabel();
        var vSpacer1 = new Spacer();
        txtSubject = new JTextField();
        var label2 = new JLabel();
        txtData = new JTextField();
        publishButton = new JButton();
        btnStop = new JButton();
        chkLoop = new JCheckBox();
        txtTotalMsgs = new JTextField();
        txtMsgPerSec = new JTextField();
        var panel7 = new JPanel();
        var panel8 = new JPanel();
        var label3 = new JLabel();
        txtPort = new JTextField();
        btnStart = new JButton();
        var panel9 = new JPanel();
        var panel10 = new JPanel();
        var scrollPane1 = new JScrollPane();
        textArea1 = new JTextArea();

        //======== contentPane ========
        {
            contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(10, 10, 10, 10), -1, -1));

            //======== panel1 ========
            {
                panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
                panel1.add(hSpacer1, new GridConstraints(0, 0, 1, 1,
                    GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                    GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                    GridConstraints.SIZEPOLICY_CAN_SHRINK,
                    null, null, null));

                //======== panel2 ========
                {
                    panel2.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));

                    //---- buttonOK ----
                    buttonOK.setText("OK");
                    panel2.add(buttonOK, new GridConstraints(0, 0, 1, 1,
                        GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                        GridConstraints.SIZEPOLICY_FIXED,
                        null, null, null));

                    //---- buttonCancel ----
                    buttonCancel.setText("Cancel");
                    panel2.add(buttonCancel, new GridConstraints(0, 1, 1, 1,
                        GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                        GridConstraints.SIZEPOLICY_FIXED,
                        null, null, null));
                }
                panel1.add(panel2, new GridConstraints(0, 1, 1, 1,
                    GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                    null, null, null));
            }
            contentPane.add(panel1, new GridConstraints(1, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK,
                null, null, null));

            //======== panel3 ========
            {
                panel3.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));

                //======== tabbedPane1 ========
                {

                    //======== panel4 ========
                    {
                        panel4.setLayout(new BorderLayout());

                        //======== panel5 ========
                        {
                            panel5.setBorder(new TitledBorder("Subscriptions"));
                            panel5.setLayout(new BorderLayout());

                            //======== scrollPane2 ========
                            {
                                scrollPane2.setViewportView(listSubscriptions);
                            }
                            panel5.add(scrollPane2, BorderLayout.CENTER);
                        }
                        panel4.add(panel5, BorderLayout.WEST);

                        //======== panel6 ========
                        {
                            panel6.setLayout(new GridLayoutManager(6, 2, new Insets(0, 0, 0, 0), -1, -1));

                            //---- label1 ----
                            label1.setText("Subject");
                            panel6.add(label1, new GridConstraints(0, 0, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_FIXED,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));
                            panel6.add(vSpacer1, new GridConstraints(4, 0, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK,
                                GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                                null, null, null));
                            panel6.add(txtSubject, new GridConstraints(1, 0, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));

                            //---- label2 ----
                            label2.setText("Data");
                            panel6.add(label2, new GridConstraints(2, 0, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_FIXED,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));
                            panel6.add(txtData, new GridConstraints(3, 0, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));

                            //---- publishButton ----
                            publishButton.setText("Publish");
                            panel6.add(publishButton, new GridConstraints(0, 1, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));

                            //---- btnStop ----
                            btnStop.setText("Stop");
                            panel6.add(btnStop, new GridConstraints(1, 1, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                null, null, null));

                            //---- chkLoop ----
                            chkLoop.setText("loop");
                            panel6.add(chkLoop, new GridConstraints(2, 1, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                null, null, null));

                            //---- txtTotalMsgs ----
                            txtTotalMsgs.setToolTipText("Total Message Count");
                            panel6.add(txtTotalMsgs, new GridConstraints(3, 1, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                null, new Dimension(80, 0), null));

                            //---- txtMsgPerSec ----
                            txtMsgPerSec.setToolTipText("Messages Per Second");
                            panel6.add(txtMsgPerSec, new GridConstraints(4, 1, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                null, new Dimension(80, 0), null));
                        }
                        panel4.add(panel6, BorderLayout.CENTER);
                    }
                    tabbedPane1.addTab("Publisher", panel4);

                    //======== panel7 ========
                    {
                        panel7.setLayout(new BorderLayout());

                        //======== panel8 ========
                        {
                            panel8.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));

                            //---- label3 ----
                            label3.setText("Port");
                            panel8.add(label3, new GridConstraints(0, 0, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                                GridConstraints.SIZEPOLICY_FIXED,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));
                            panel8.add(txtPort, new GridConstraints(0, 1, 1, 1,
                                GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                                GridConstraints.SIZEPOLICY_CAN_GROW | GridConstraints.SIZEPOLICY_WANT_GROW,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));

                            //---- btnStart ----
                            btnStart.setText("Start");
                            panel8.add(btnStart, new GridConstraints(0, 2, 1, 1,
                                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                                GridConstraints.SIZEPOLICY_FIXED,
                                null, null, null));
                        }
                        panel7.add(panel8, BorderLayout.NORTH);

                        //======== panel9 ========
                        {
                            panel9.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
                        }
                        panel7.add(panel9, BorderLayout.SOUTH);

                        //======== panel10 ========
                        {
                            panel10.setLayout(new BorderLayout());

                            //======== scrollPane1 ========
                            {
                                scrollPane1.setViewportView(textArea1);
                            }
                            panel10.add(scrollPane1, BorderLayout.CENTER);
                        }
                        panel7.add(panel10, BorderLayout.CENTER);
                    }
                    tabbedPane1.addTab("RMI Server", panel7);
                }
                panel3.add(tabbedPane1, new GridConstraints(0, 0, 1, 1,
                    GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                    GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                    null, new Dimension(200, 200), null));
            }
            contentPane.add(panel3, new GridConstraints(0, 0, 1, 1,
                GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                null, null, null));
        }
        // JFormDesigner - End of component initialization  //GEN-END:initComponents  @formatter:on
    }

    // JFormDesigner - Variables declaration - DO NOT MODIFY  //GEN-BEGIN:variables  @formatter:off
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JTabbedPane tabbedPane1;
    private JScrollPane scrollPane2;
    private JList listSubscriptions;
    private JTextField txtSubject;
    private JTextField txtData;
    private JButton publishButton;
    private JButton btnStop;
    private JCheckBox chkLoop;
    private JTextField txtTotalMsgs;
    private JTextField txtMsgPerSec;
    private JTextField txtPort;
    private JButton btnStart;
    private JTextArea textArea1;
    // JFormDesigner - End of variables declaration  //GEN-END:variables  @formatter:on
}