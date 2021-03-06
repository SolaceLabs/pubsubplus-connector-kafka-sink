package com.solace.connector.kafka.connect.sink.it;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.FileBasedConfiguration;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class SolaceConnectorDeployment implements TestConstants {

  static Logger logger = LoggerFactory.getLogger(SolaceConnectorDeployment.class.getName());

  static String kafkaTestTopic = KAFKA_SINK_TOPIC + "-" + Instant.now().getEpochSecond();
  OkHttpClient client = new OkHttpClient();
  String connectorAddress = new TestConfigProperties().getProperty("kafka.connect_rest_url");

  public void waitForConnectorRestIFUp() {
    Request request = new Request.Builder().url("http://" + connectorAddress + "/connector-plugins").build();
    Response response = null;
    do {
      try {
        Thread.sleep(1000l);
        response = client.newCall(request).execute();
      } catch (IOException | InterruptedException e) {
        // Continue looping
      }
    } while (response == null || !response.isSuccessful());
  }

  public void provisionKafkaTestTopic() {
    // Create a new kafka test topic to use
    String bootstrapServers = MessagingServiceFullLocalSetupConfluent.COMPOSE_CONTAINER_KAFKA.getServiceHost("kafka_1",
        39092) + ":39092";
    Properties properties = new Properties();
    properties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    AdminClient adminClient = AdminClient.create(properties);
    NewTopic newTopic = new NewTopic(kafkaTestTopic, 1, (short) 1); // new NewTopic(topicName, numPartitions,
                                                                    // replicationFactor)
    List<NewTopic> newTopics = new ArrayList<NewTopic>();
    newTopics.add(newTopic);
    adminClient.createTopics(newTopics);
    adminClient.close();
  }

  void startConnector() {
    startConnector(null); // Defaults only, no override
  }

  void startConnector(Properties props) {
    String configJson = null;
    // Prep config files
    try {
      // Configure .json connector params
      File jsonFile = new File(
          UNZIPPEDCONNECTORDESTINATION + "/" + Tools.getUnzippedConnectorDirName() + "/" + CONNECTORJSONPROPERTIESFILE);
      String jsonString = FileUtils.readFileToString(jsonFile);
      JsonElement jtree = new JsonParser().parse(jsonString);
      JsonElement jconfig = jtree.getAsJsonObject().get("config");
      JsonObject jobject = jconfig.getAsJsonObject();
      // Set properties defaults
      jobject.addProperty("sol.host", "tcp://" + new TestConfigProperties().getProperty("sol.host") + ":55555");
      jobject.addProperty("sol.username", SOL_ADMINUSER_NAME);
      jobject.addProperty("sol.password", SOL_ADMINUSER_PW);
      jobject.addProperty("sol.vpn_name", SOL_VPN);
      jobject.addProperty("topics", kafkaTestTopic);
      jobject.addProperty("sol.topics", SOL_TOPICS);
      jobject.addProperty("sol.autoflush.size", "1");
      jobject.addProperty("sol.message_processor_class", CONN_MSGPROC_CLASS);
      jobject.addProperty("sol.kafka_message_key", CONN_KAFKA_MSGKEY);
      jobject.addProperty("value.converter", "org.apache.kafka.connect.converters.ByteArrayConverter");
      jobject.addProperty("key.converter", "org.apache.kafka.connect.storage.StringConverter");
      // Override properties if provided
      if (props != null) {
        props.forEach((key, value) -> {
          jobject.addProperty((String) key, (String) value);
        });
      }
      Gson gson = new Gson();
      configJson = gson.toJson(jtree);
    } catch (IOException e) {
      e.printStackTrace();
    }

    // Configure and start the solace connector
    try {
      // check presence of Solace plugin: curl
      // http://18.218.82.209:8083/connector-plugins | jq
      Request request = new Request.Builder().url("http://" + connectorAddress + "/connector-plugins").build();
      Response response;
      response = client.newCall(request).execute();
      assert (response.isSuccessful());
      String results = response.body().string();
      logger.info("Available connector plugins: " + results);
      assert (results.contains("solace"));

      // Delete a running connector, if any
      Request deleterequest = new Request.Builder()
          .url("http://" + connectorAddress + "/connectors/solaceSinkConnector").delete().build();
      Response deleteresponse = client.newCall(deleterequest).execute();
      logger.info("Delete response: " + deleteresponse);

      // configure plugin: curl -X POST -H "Content-Type: application/json" -d
      // @solace_source_properties.json http://18.218.82.209:8083/connectors
      Request configrequest = new Request.Builder().url("http://" + connectorAddress + "/connectors")
          .post(RequestBody.create(configJson, MediaType.parse("application/json"))).build();
      Response configresponse = client.newCall(configrequest).execute();
      // if (!configresponse.isSuccessful()) throw new IOException("Unexpected code "
      // + configresponse);
      String configresults = configresponse.body().string();
      logger.info("Connector config results: " + configresults);
      // check success
      Thread.sleep(5000); // Give some time to start
    } catch (IOException e) {
      e.printStackTrace();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

}
