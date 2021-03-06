package com.sf.monitor.zk;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sf.exec.SystemCommandExecutor;
import com.sf.log.Logger;
import com.sf.monitor.Config;
import com.sf.monitor.Resources;
import com.sf.monitor.utils.JsonValues;
import com.sf.monitor.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ZookeeperHosts {
  private static final Logger log = new Logger(ZookeeperHosts.class);
  private static final String StatCommand = "stat";
  private static final String MntrCommand = "mntr";
  private static final String RuokCommand = "ruok";

  private final List<ZookeeperHost> zkHosts;

  public ZookeeperHosts(String addrs) {
    this.zkHosts = Lists.transform(
      Lists.newArrayList(addrs.trim().split(",")), new Function<String, ZookeeperHost>() {
        @Override
        public ZookeeperHost apply(String input) {
          String[] ss = input.trim().split(":", 2);
          return new ZookeeperHost(ss[0], Integer.valueOf(ss[1]));
        }
      }
    );
  }

  public List<JsonValues> hostInfos() {
    return Lists.transform(
      zkHosts, new Function<ZookeeperHost, JsonValues>() {
        @Override
        public JsonValues apply(ZookeeperHost host) {
          return host.getInfo();
        }
      }
    );
  }

  public String sendCommand(String host, String cmd) {
    for (ZookeeperHost zkHost : zkHosts) {
      if (zkHost.toString().equals(host)) {
        return zkHost.command(cmd);
      }
    }
    return String.format("Zookeeper host [%s] not found!", host);
  }

  public void pulse() {
    for (ZookeeperHost host : zkHosts) {
      host.pulse();
    }
  }

  static class ZookeeperHost {
    public String hostName;
    public int port;
    private boolean problem;

    public ZookeeperHost(String hostName, int port) {
      this.hostName = hostName;
      this.port = port;

    }

    @Override
    public String toString() {
      return hostName + ":" + port;
    }

    public void pulse() {
      String reply = command(RuokCommand);
      if (!"imok".equals(reply)) {
        String warnMsg = String.format("zookeeper host[%s] ping reply: [%s]", toString(), reply);
        Utils.sendNotify("zookeeper", warnMsg);
        log.warn(warnMsg);
      }
    }

    public boolean isOk() {
      return "imok".equals(command(RuokCommand));
    }

    public JsonValues getInfo() {
      String infoMsg = command(MntrCommand);
      Map<String, String> metrics = Maps.newHashMap();
      for (String kvStr : infoMsg.split("\n")) {
        if (kvStr.isEmpty()) {
          continue;
        }
        String[] kv = kvStr.split("\t");
        if (kv.length == 2) {
          metrics.put(kv[0], kv[1]);
        }
      }

      metrics.put("isOk", isOk() ? "ok" : "error");
      metrics.put("host", toString());

      return JsonValues.of(
        metrics,
        "host",
        "isOk",
        "zk_server_state",
        "zk_version",
        "zk_avg_latency",
        "zk_max_latency",
        "zk_min_latency",
        "zk_packets_received",
        "zk_packets_sent",
        "zk_outstanding_requests",
        "zk_znode_count",
        "zk_watch_count",
        "zk_ephemerals_count",
        "zk_approximate_data_size",
        "zk_followers",
        "zk_synced_followers",
        "zk_pending_syncs",
        "zk_open_file_descriptor_count",
        "zk_max_file_descriptor_count"
      );
    }

    public String command(String command) {
      try {
        List<String> commands = new ArrayList<String>();
        commands.add("/bin/sh");
        commands.add("-c");
        commands.add(String.format("echo %s | nc %s %d", command, hostName, port));

        SystemCommandExecutor executor = new SystemCommandExecutor(commands);
        int result = executor.executeCommand();
        String err = executor.getStandardErrorFromCommand();
        String res = executor.getStandardOutputFromCommand();
        if (result == 0) {
          return res.trim();
        } else {
          log.error(
            "send 4LTR command[%s] to [%s:%d] failed, error code: %d, error msg: %s",
            command,
            hostName,
            port,
            result,
            err
          );
          return err;
        }

        // The following code could throw "Connection reset" time to time,
        // so use system command instead temporary!

        //Socket socket = new Socket(hostName, port);
        //OutputStream os = socket.getOutputStream();
        //IOUtils.write(command + "\n", os);
        //os.flush();
        //String resp = IOUtils.toString(socket.getInputStream());
        //IOUtils.closeQuietly(socket);
        //return resp;

      } catch (Exception e) {
        log.error(e, "send 4LTR command[%s] to [%s:%d] failed", command, hostName, port);
        return e.getMessage();
      }
    }
  }

  public static void main(String[] args) throws Exception {
    Config.init("config");
    Resources.init();

    final ZookeeperHosts host = new ZookeeperHosts("192.168.10.60:2181,192.168.10.41:2181,192.168.10.42:2181");
    System.out.println("series: " + Resources.jsonMapper.writeValueAsString(host.hostInfos()));

  }

}
