package com.group8.meetingall.dto.xfasr;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
public class Decoder {
    private Text[] texts;
    private int defc = 10;
    public Decoder() {
        this.texts = new Text[this.defc];
    }
    public synchronized void decode(Text text) {
        if (text.sn >= this.defc) {
            this.resize();
        }
        this.texts[text.sn] = text;
    }
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Text t : this.texts) {
            if (t != null && !t.deleted) {
                sb.append(t.text);
            }
        }
        return sb.toString();
    }
    public void resize() {
        int oc = this.defc;
        this.defc <<= 1;
        Text[] old = this.texts;
        this.texts = new Text[this.defc];
        for (int i = 0; i < oc; i++) {
            this.texts[i] = old[i];
        }
    }
    public void discard(){
        for(int i=0;i<this.texts.length;i++){
            this.texts[i]= null;
        }
    }
}
