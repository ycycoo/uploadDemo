package com.model;

import org.codehaus.jackson.map.annotate.JacksonStdImpl;

@JacksonStdImpl
public class FileChunk {
	
private String name;

private String originName;

private long size;

private String type;


public String getName() {
	return name;
}

public void setName(String name) {
	this.name = name;
}

public long getSize() {
	return size;
}

public void setSize(long size) {
	this.size = size;
}

public String getType() {
	return type;
}

public void setType(String type) {
	this.type = type;
}

/**
 * @return the originName
 */
public String getOriginName() {
	return originName;
}

/**
 * @param originName the originName to set
 */
public void setOriginName(String originName) {
	this.originName = originName;
}


}
