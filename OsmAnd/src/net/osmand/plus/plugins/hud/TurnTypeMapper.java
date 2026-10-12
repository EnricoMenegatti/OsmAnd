package net.osmand.plus.plugins.hud;

import net.osmand.router.TurnType;

public class TurnTypeMapper {
	
	public static int mapTurnType(TurnType turnType) {
		if (turnType == null) {
			return 0;
		}
		
		int value = turnType.getValue();
		
		switch (value) {
			case TurnType.C:
				return 1;
			case TurnType.TSLL:
				return 2;
			case TurnType.TL:
				return 3;
			case TurnType.TSHL:
				return 4;
			case TurnType.TR:
				return 6;
			case TurnType.TSLR:
				return 5;
			case TurnType.TSHR:
				return 7;
			case TurnType.TU:
				return 8;
			case TurnType.TRU:
				return 9;
			case TurnType.KL:
				return 10;
			case TurnType.KR:
				return 11;
			case TurnType.RNDB:
				return 12;
			case TurnType.RNLB:
				return 13;
			case TurnType.OFFR:
				return 21;
			default:
				return 0;
		}
	}
	
	public static int getExitNumber(TurnType turnType) {
		if (turnType == null || !turnType.isRoundAbout()) {
			return 0;
		}
		return Math.max(0, turnType.getExitOut());
	}
}
