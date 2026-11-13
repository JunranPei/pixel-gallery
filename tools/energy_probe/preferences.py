"""Read the string value in a saved AndroidX Preferences protobuf."""


def fields(data):
    position=0
    def varint():
        nonlocal position
        value=0
        for shift in range(0,70,7):
            if position>=len(data): raise ValueError('Truncated protobuf varint')
            x=data[position];position+=1;value|=(x&127)<<shift
            if x<128:return value
        raise ValueError('Oversized protobuf varint')
    while position<len(data):
        key=varint();number,wire=key>>3,key&7
        if number==0: raise ValueError('Invalid protobuf field number')
        if wire==0:value=varint()
        elif wire in (1,2,5):
            size=varint() if wire==2 else {1:8,5:4}[wire]
            if position+size>len(data):raise ValueError('Truncated protobuf payload')
            value=data[position:position+size];position+=size
        else:raise ValueError('Unsupported protobuf wire type')
        yield number,wire,value


def string_preference(data,key):
    for number,wire,entry in fields(data):
        if number!=1 or wire!=2:continue
        f={n:v for n,w,v in fields(entry) if w==2}
        if f.get(1)!=key.encode('utf-8'):continue
        return next((v.decode('utf-8') for n,w,v in fields(f.get(2,b'')) if n==5 and w==2),None)
    return None
