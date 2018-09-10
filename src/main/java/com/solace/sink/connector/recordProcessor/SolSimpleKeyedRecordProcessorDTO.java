/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.solace.sink.connector.recordProcessor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.solace.sink.connector.SolRecordProcessor;
import com.solacesystems.jcsmp.BytesXMLMessage;
import com.solacesystems.jcsmp.JCSMPFactory;

public class SolSimpleKeyedRecordProcessorDTO implements SolRecordProcessor {
	
	private static final Logger log = LoggerFactory.getLogger(SolSimpleKeyedRecordProcessor.class);

	public enum KeyHeader {
		NONE, DESTINATION, CORRELATION_ID, CORRELATION_ID_AS_BYTES
	};
	protected KeyHeader keyheader = KeyHeader.NONE;

	@Override
	public BytesXMLMessage processRecord(String sKey, SinkRecord record) {
		if (sKey.equals("NONE")) {
			this.keyheader = KeyHeader.NONE;
		} else if (sKey.equals("DESTINATION")) {
			this.keyheader = KeyHeader.DESTINATION;
		} else if (sKey.equals("CORRELATION_ID")) {
			this.keyheader = KeyHeader.CORRELATION_ID;
		} else if (sKey.equals("CORRELATION_ID_AS_BYTES")) {
			this.keyheader = KeyHeader.CORRELATION_ID_AS_BYTES;
		}
		
		BytesXMLMessage msg = JCSMPFactory.onlyInstance().createMessage(BytesXMLMessage.class);
		
		Schema s = record.valueSchema();
		Object v = record.value();
		Schema sk = record.keySchema();
		Object vk = record.key();
		String kafkaTopic = record.topic();

		msg.setUserData(kafkaTopic.getBytes(StandardCharsets.UTF_8)); //add the original Kafka Topic to the binary user data
		msg.setApplicationMessageType("ResendOfKakfaTopic: " + kafkaTopic);
		msg.setDeliverToOne(true); //Added DTO flag for topic consumer scaling

		
		//If Topic was Keyed, use the key for correlationID
		if (keyheader != KeyHeader.NONE && keyheader != KeyHeader.DESTINATION) {
			

			if (vk != null) {
				if (sk == null) {
					log.trace("No schema info {}", vk);
					if (vk instanceof byte[]) {                     
						msg.setCorrelationId(new String((byte[])vk, StandardCharsets.UTF_8));
					}
					else if (vk instanceof ByteBuffer) {
						msg.setCorrelationId(new String(((ByteBuffer)vk).array(), StandardCharsets.UTF_8));
					}
					else {
						msg.setCorrelationId(vk.toString());
					}
				}
				else if (sk.type() == Schema.Type.BYTES) {
					if (vk instanceof byte[]) {
						msg.setCorrelationId(new String((byte[])vk, StandardCharsets.UTF_8));
					}
					else if (vk instanceof ByteBuffer) {
						msg.setCorrelationId(new String(((ByteBuffer)vk).array(), StandardCharsets.UTF_8));
					}
				}

				else if (sk.type() == Schema.Type.STRING) {
					msg.setCorrelationId((String)vk);
				}
			}

		} else if(keyheader == KeyHeader.DESTINATION && sk.type() == Schema.Type.STRING) {
				msg.setCorrelationId((String)vk);
		}

		//get message body details from record
		log.debug("Value schema {}", s);
		if (v == null) {
			msg.reset();
			return msg;
		}
		else if (s == null) {
			log.debug("No schema info {}", v);
			if (v instanceof byte[]) {        
				msg.writeAttachment((byte[]) v);

			}
			else if (v instanceof ByteBuffer) {
				msg.writeAttachment((byte[]) ((ByteBuffer) v).array());
			}
		}
		else if (s.type() == Schema.Type.BYTES) {
			if (v instanceof byte[]) {
				msg.writeAttachment((byte[]) v);
			}
			else if (v instanceof ByteBuffer) {
				msg.writeAttachment((byte[]) ((ByteBuffer) v).array());
			}
		}


		return msg;
	}



}
